pub mod balancer;
pub mod cfproxy;
pub mod config;
pub mod crypto;
pub mod faketls;
pub mod network_operations;
pub mod proxy;
pub mod ws;

use config::*;
use once_cell::sync::OnceCell;
use parking_lot::Mutex;
use proxy::{parse_cidr_pool, run_proxy, ProxyPools};
use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int};
use std::sync::atomic::Ordering;
use std::sync::Arc;
use tokio::runtime::Runtime;
use tokio_util::sync::CancellationToken;

static RUNTIME: OnceCell<Runtime> = OnceCell::new();

struct ProxyState {
    pools: Arc<ProxyPools>,
    handle: tokio::task::JoinHandle<()>,
    cancel_tasks: CancellationToken,
}

static STATE: OnceCell<Mutex<Option<ProxyState>>> = OnceCell::new();

fn state_cell() -> &'static Mutex<Option<ProxyState>> {
    STATE.get_or_init(|| Mutex::new(None))
}

fn runtime() -> &'static Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(4)
            .thread_name("tgwsproxy-rt")
            .enable_all()
            .build()
            .expect("failed to build global tokio runtime")
    })
}

fn cstr_to_string(p: *const c_char) -> String {
    if p.is_null() {
        return String::new();
    }
    unsafe { CStr::from_ptr(p).to_string_lossy().into_owned() }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn StartProxy(
    c_host: *const c_char,
    port: c_int,
    c_dc_ips: *const c_char,
    c_secret: *const c_char,
    verbose: c_int,
) -> c_int {
    let cell = state_cell();
    let mut guard = cell.lock();

    if guard.is_some() {
        return -1;
    }

    let host = cstr_to_string(c_host);
    let go_port = port as u16;
    let dc_ips_str = cstr_to_string(c_dc_ips);
    let secret_str = cstr_to_string(c_secret);
    let is_verbose = verbose != 0;

    init_logging(is_verbose);
    cfproxy::clear_cfproxy_429_cooldowns();

    if secret_str.len() == 32 {
        if hex::decode(&secret_str).is_ok() {
            *PROXY_SECRET.write() = secret_str.clone();
        }
    }

    cfproxy::init_cfproxy_domains();

    let dc_opt_map: HashMap<i32, String> = parse_cidr_pool(&dc_ips_str);

    let rt = runtime();
    let cancel_tasks = CancellationToken::new();
    let pools = Arc::new(ProxyPools::new(cancel_tasks.clone()));
    let (tx, rx) = std::sync::mpsc::channel::<Result<(), String>>();

    let pools_task = pools.clone();
    let host_task = host.clone();
    let map_task = dc_opt_map.clone();
    let cancel_root = cancel_tasks.clone();

    let handle = rt.spawn(async move {
        let addr = format!("{}:{}", host_task, go_port);
        match tokio::net::TcpListener::bind(&addr).await {
            Ok(listener) => {
                let _ = tx.send(Ok(()));
                if let Err(e) = run_proxy(
                    pools_task,
                    host_task,
                    go_port,
                    map_task,
                    cancel_root,
                    listener,
                )
                .await
                {
                    lerror!("listen on {}: {}", addr, e);
                }
            }
            Err(e) => {
                let _ = tx.send(Err(format!("listen on {}: {}", addr, e)));
            }
        }
    });

    match rx.recv() {
        Ok(Ok(())) => {}
        Ok(Err(_)) => {
            handle.abort();
            return -3;
        }
        Err(_) => {
            handle.abort();
            return -3;
        }
    }

    *guard = Some(ProxyState {
        pools,
        handle,
        cancel_tasks,
    });

    0
}

#[unsafe(no_mangle)]
pub extern "C" fn StopProxy() -> c_int {
    let cell = state_cell();
    let mut guard = cell.lock();

    let state = match guard.take() {
        Some(s) => s,
        None => return -1,
    };

    linfo!("StopProxy: cancelling all tasks");
    state.cancel_tasks.cancel();

    let rt = runtime();
    let pools = state.pools.clone();
    let handle = state.handle;
    rt.block_on(async move {
        linfo!("StopProxy: waiting for proxy tasks to finish (max 2s)");
        let _ = tokio::time::timeout(std::time::Duration::from_secs(2), handle).await;
        linfo!("StopProxy: closing pool connections");
        pools.close_all().await;
        pools.reset().await;
        linfo!("StopProxy: done");
    });

    STATS.reset();
    WS_BLACKLIST.write().clear();
    DC_FAIL_UNTIL.write().clear();
    IP_FAIL_UNTIL.write().clear();
    cfproxy::clear_cfproxy_429_cooldowns();
    proxy::set_ws_pool_fronting_first(true);

    linfo!("StopProxy: exit");
    0
}

#[unsafe(no_mangle)]
pub extern "C" fn SetPoolSize(size: c_int) {
    let mut n = size;
    if n < 0 {
        n = 0;
    }
    if n > 16 {
        n = 16;
    }
    POOL_SIZE.store(n, Ordering::Relaxed);
}

#[unsafe(no_mangle)]
pub extern "C" fn SetBufferSizeKb(kb: c_int) {
    let mut n = kb;
    if n < 4 {
        n = 4;
    }
    if n > 4096 {
        n = 4096;
    }
    BUFFER_SIZE.store(n * 1024, Ordering::Relaxed);
    RECV_BUF.store(n * 1024, Ordering::Relaxed);
    SEND_BUF.store(n * 1024, Ordering::Relaxed);
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn SetCfProxyCacheDir(c_cache_dir: *const c_char) {
    let dir = cstr_to_string(c_cache_dir);
    CFPROXY.write().cache_dir = dir.trim().to_string();
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn SetCfProxyConfig(
    enabled: c_int,
    _priority: c_int,
    c_user_domain: *const c_char,
) {
    CFPROXY_ENABLED.store(enabled != 0, Ordering::Relaxed);
    let user_domain = cstr_to_string(c_user_domain);
    let list = coerce_domain_list_str(&user_domain);
    let mut cfg = CFPROXY.write();
    cfg.user_domain = user_domain.trim().to_string();
    if !list.is_empty() {
        cfg.domains = list.clone();
        cfg.active = list[0].clone();
        crate::balancer::BALANCER
            .write()
            .update_domains_list(&cfg.domains);
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn SetCfWorkerDomains(c_domains: *const c_char) {
    let raw = cstr_to_string(c_domains);
    *CF_WORKER_DOMAINS.write() = coerce_domain_list_str(&raw);
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn SetFakeTlsDomain(c_domain: *const c_char) {
    let d = cstr_to_string(c_domain);
    *FAKE_TLS_DOMAIN.write() = d.trim().to_string();
}

#[unsafe(no_mangle)]
pub extern "C" fn SetDisableSecure(v: c_int) {
    DISABLE_SECURE.store(v != 0, Ordering::Relaxed);
}

#[unsafe(no_mangle)]
pub extern "C" fn SetForceTestDc(v: c_int) {
    FORCE_TEST_DC.store(v != 0, Ordering::Relaxed);
}

#[unsafe(no_mangle)]
pub extern "C" fn SetProxyProtocol(v: c_int) {
    PROXY_PROTOCOL.store(v != 0, Ordering::Relaxed);
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn SetSecret(c_secret: *const c_char) {
    let s = cstr_to_string(c_secret);
    if s.len() != 32 {
        return;
    }
    if hex::decode(&s).is_err() {
        return;
    }
    *PROXY_SECRET.write() = s;
}

#[unsafe(no_mangle)]
pub extern "C" fn GetStats() -> *mut c_char {
    let s = STATS.summary();
    CString::new(s).unwrap_or_default().into_raw()
}

#[unsafe(no_mangle)]
pub extern "C" fn GetSecretWithPrefix() -> *mut c_char {
    let sec = PROXY_SECRET.read().clone();
    let ftls = FAKE_TLS_DOMAIN.read().clone();
    let full = if ftls.is_empty() {
        format!("dd{}", sec)
    } else {
        format!(
            "ee{}{}",
            sec,
            ftls.as_bytes()
                .iter()
                .map(|b| format!("{:02x}", b))
                .collect::<String>()
        )
    };
    CString::new(full).unwrap_or_default().into_raw()
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn FreeString(p: *mut c_char) {
    if p.is_null() {
        return;
    }
    unsafe {
        let _ = CString::from_raw(p);
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn FetchDomainList(output: *mut u8, capacity: c_int) -> c_int {
    if output.is_null() || capacity <= 0 {
        return -3;
    }
    let output = unsafe { std::slice::from_raw_parts_mut(output, capacity as usize) };
    match runtime().block_on(network_operations::fetch_domain_list(output)) {
        Ok(length) => length as c_int,
        Err(network_operations::DomainListError::HttpStatus(status)) => -(1000 + status as c_int),
        Err(network_operations::DomainListError::TooLarge) => -2,
        Err(
            network_operations::DomainListError::Request(_)
            | network_operations::DomainListError::ResponseTimeout,
        ) => -1,
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn RunTransferTest(
    c_service: *const c_char,
    c_host: *const c_char,
    c_resolved_ip: *const c_char,
    c_path: *const c_char,
    attempt: c_int,
    payload_kib: c_int,
    host_timeout_ms: c_int,
    idle_timeout_seconds: c_int,
) -> *mut c_char {
    let service = cstr_to_string(c_service);
    let host = cstr_to_string(c_host);
    let resolved_ip = cstr_to_string(c_resolved_ip);
    let path = cstr_to_string(c_path);
    let result = runtime().block_on(network_operations::run_transfer_test(
        network_operations::TransferTestRequest {
            service,
            host,
            resolved_ip,
            path,
            attempt,
            payload_kib,
            host_timeout_ms,
            idle_timeout_seconds,
        },
    ));
    match result.and_then(|result| CString::new(result).ok()) {
        Some(value) => value.into_raw(),
        None => std::ptr::null_mut(),
    }
}
