use reqwest::header::{ACCEPT, ACCEPT_ENCODING, CACHE_CONTROL, RANGE, USER_AGENT};
use reqwest::redirect::Policy;
use reqwest::Client;
use serde::Serialize;
use std::net::{IpAddr, SocketAddr};
use std::time::{Duration, Instant};

const DOMAIN_LIST_URL: &str = "https://raw.githubusercontent.com/skytr1x/Obhod/main/list/list.json";
const MAX_DOMAIN_LIST_BYTES: usize = 8 * 1024 * 1024;
const SOCKS_PROXY: &str = "socks5://127.0.0.1:10800";

#[derive(Debug)]
pub enum DomainListError {
    Request(reqwest::Error),
    ResponseTimeout,
    HttpStatus(u16),
    TooLarge,
}

pub async fn fetch_domain_list(output: &mut [u8]) -> Result<usize, DomainListError> {
    let client = Client::builder()
        .connect_timeout(Duration::from_secs(10))
        .redirect(Policy::none())
        .build()
        .map_err(DomainListError::Request)?;
    let mut response = tokio::time::timeout(
        Duration::from_secs(10),
        client
            .get(DOMAIN_LIST_URL)
            .header(ACCEPT, "application/json")
            .header(USER_AGENT, "Obhod-Android")
            .send(),
    )
    .await
    .map_err(|_| DomainListError::ResponseTimeout)?
    .map_err(DomainListError::Request)?;
    if response.status().as_u16() != 200 {
        return Err(DomainListError::HttpStatus(response.status().as_u16()));
    }

    let limit = output.len().min(MAX_DOMAIN_LIST_BYTES);
    let mut written = 0;
    loop {
        let chunk = tokio::time::timeout(Duration::from_secs(10), response.chunk())
            .await
            .map_err(|_| DomainListError::ResponseTimeout)?
            .map_err(DomainListError::Request)?;
        let Some(chunk) = chunk else {
            return Ok(written);
        };
        if chunk.len() > limit - written {
            return Err(DomainListError::TooLarge);
        }
        output[written..written + chunk.len()].copy_from_slice(&chunk);
        written += chunk.len();
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct TransferTestResult {
    service: String,
    attempt: i32,
    outcome: String,
    bytes_received: usize,
    elapsed_ms: u64,
    doh_resolve_ms: Option<u64>,
    socks_connect_ms: Option<u64>,
    tls_handshake_ms: Option<u64>,
    transfer_ms: Option<u64>,
    http_status: Option<u16>,
}

pub struct TransferTestRequest {
    pub service: String,
    pub host: String,
    pub resolved_ip: String,
    pub path: String,
    pub attempt: i32,
    pub payload_kib: i32,
    pub host_timeout_ms: i32,
    pub idle_timeout_seconds: i32,
}

impl TransferTestResult {
    fn new(service: String, attempt: i32, started: Instant, outcome: &str) -> Self {
        Self {
            service,
            attempt,
            outcome: outcome.to_owned(),
            bytes_received: 0,
            elapsed_ms: elapsed_ms(started),
            doh_resolve_ms: None,
            socks_connect_ms: None,
            tls_handshake_ms: None,
            transfer_ms: None,
            http_status: None,
        }
    }

    fn json(self) -> Option<String> {
        serde_json::to_string(&self).ok()
    }
}

pub async fn run_transfer_test(request: TransferTestRequest) -> Option<String> {
    let TransferTestRequest {
        service,
        host,
        resolved_ip,
        path,
        attempt,
        payload_kib,
        host_timeout_ms,
        idle_timeout_seconds,
    } = request;
    let started = Instant::now();
    let mut result = TransferTestResult::new(service, attempt, started, "invalid_config");
    if attempt <= 0
        || !(100..=200).contains(&payload_kib)
        || ![2, 4, 8].contains(&idle_timeout_seconds)
        || !(1..=15_000).contains(&host_timeout_ms)
        || !valid_host(&host)
        || resolved_ip.parse::<IpAddr>().is_err()
        || !path.starts_with('/')
        || path
            .bytes()
            .any(|byte| byte.is_ascii_control() || byte == b' ')
    {
        return result.json();
    }

    let max_bytes = payload_kib as usize * 1024;
    let url = format!("https://{host}{path}");
    let resolved_ip = match resolved_ip.parse::<IpAddr>() {
        Ok(address) => address,
        Err(_) => return result.json(),
    };
    let client = match transfer_client(&host, resolved_ip, SOCKS_PROXY, host_timeout_ms) {
        Ok(client) => client,
        Err(_) => {
            result.outcome = "proxy_unavailable".to_owned();
            return result.json();
        }
    };

    let mut response = match client
        .get(url)
        .header(ACCEPT, "*/*")
        .header(ACCEPT_ENCODING, "identity")
        .header(RANGE, format!("bytes=0-{}", max_bytes - 1))
        .header(CACHE_CONTROL, "no-cache")
        .header(reqwest::header::CONNECTION, "close")
        .send()
        .await
    {
        Ok(response) => response,
        Err(error) => {
            result.outcome = if error.is_timeout() {
                if elapsed_ms(started) >= host_timeout_ms as u64 {
                    "overall_timeout".to_owned()
                } else {
                    "connect_timeout".to_owned()
                }
            } else {
                "proxy_unavailable".to_owned()
            };
            result.elapsed_ms = elapsed_ms(started);
            return result.json();
        }
    };

    result.http_status = Some(response.status().as_u16());
    if !(200..300).contains(&response.status().as_u16()) {
        result.outcome = "reached_http_non_2xx".to_owned();
        result.elapsed_ms = elapsed_ms(started);
        return result.json();
    }

    let transfer_started = Instant::now();
    result.transfer_ms = Some(0);
    while result.bytes_received < max_bytes {
        let next_chunk = tokio::time::timeout(
            Duration::from_secs(idle_timeout_seconds as u64),
            response.chunk(),
        )
        .await;
        match next_chunk {
            Err(_) => {
                result.outcome = if elapsed_ms(started) >= host_timeout_ms as u64 {
                    "overall_timeout"
                } else {
                    "idle_timeout"
                }
                .to_owned();
                break;
            }
            Ok(Err(error)) => {
                result.outcome = if error.is_timeout() {
                    if elapsed_ms(started) >= host_timeout_ms as u64 {
                        "overall_timeout"
                    } else {
                        "idle_timeout"
                    }
                } else {
                    "transfer_failed"
                }
                .to_owned();
                break;
            }
            Ok(Ok(None)) => {
                result.outcome = if result.bytes_received > 0 {
                    "response_complete"
                } else {
                    "empty_body"
                }
                .to_owned();
                break;
            }
            Ok(Ok(Some(chunk))) => {
                result.bytes_received += chunk.len().min(max_bytes - result.bytes_received);
            }
        }
    }
    if result.bytes_received >= max_bytes {
        result.outcome = "complete".to_owned();
    }
    result.transfer_ms = Some(elapsed_ms(transfer_started));
    result.elapsed_ms = elapsed_ms(started);
    result.json()
}

fn transfer_client(
    host: &str,
    resolved_ip: IpAddr,
    proxy_url: &str,
    host_timeout_ms: i32,
) -> Result<Client, reqwest::Error> {
    let proxy = reqwest::Proxy::all(proxy_url)?;
    Client::builder()
        .proxy(proxy)
        .http1_only()
        .connect_timeout(Duration::from_millis(host_timeout_ms.min(5_000) as u64))
        .timeout(Duration::from_millis(host_timeout_ms as u64))
        .resolve(host, SocketAddr::new(resolved_ip, 443))
        .build()
}

fn valid_host(host: &str) -> bool {
    !host.is_empty()
        && host.len() <= 253
        && host
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'-'))
        && host.split('.').all(|label| {
            !label.is_empty()
                && label.len() <= 63
                && label.as_bytes()[0].is_ascii_alphanumeric()
                && label.as_bytes()[label.len() - 1].is_ascii_alphanumeric()
        })
}

fn elapsed_ms(started: Instant) -> u64 {
    started.elapsed().as_millis().min(u64::MAX as u128) as u64
}

#[cfg(test)]
mod tests {
    use super::{transfer_client, valid_host};
    use std::net::{IpAddr, Ipv4Addr};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    #[test]
    fn accepts_target_hostnames() {
        assert!(valid_host("i.ytimg.com"));
        assert!(valid_host("speed.cloudflare.com"));
    }

    #[test]
    fn rejects_invalid_hostnames() {
        assert!(!valid_host(""));
        assert!(!valid_host("-example.com"));
        assert!(!valid_host("example.com/other"));
        assert!(!valid_host("bad..example.com"));
    }

    #[tokio::test]
    async fn rejects_invalid_transfer_options_without_network_access() {
        let result = super::run_transfer_test(super::TransferTestRequest {
            service: "youtube".to_owned(),
            host: "i.ytimg.com".to_owned(),
            resolved_ip: "203.0.113.1".to_owned(),
            path: "/image.jpg".to_owned(),
            attempt: 1,
            payload_kib: 1,
            host_timeout_ms: 15_000,
            idle_timeout_seconds: 2,
        })
        .await
        .expect("transfer result should serialize");
        let result: serde_json::Value =
            serde_json::from_str(&result).expect("transfer result should be valid JSON");

        assert_eq!(result["outcome"], "invalid_config");
        assert_eq!(result["service"], "youtube");
    }

    #[tokio::test]
    async fn uses_pre_resolved_ip_for_socks_target() {
        let listener = TcpListener::bind("127.0.0.1:0")
            .await
            .expect("local test listener should bind");
        let proxy_address = listener
            .local_addr()
            .expect("listener should have an address");
        let proxy_url = format!("socks5://{proxy_address}");
        let (target_ip_tx, target_ip_rx) = tokio::sync::oneshot::channel();
        let server = tokio::spawn(async move {
            let (mut stream, _) = listener
                .accept()
                .await
                .expect("client should connect to local SOCKS server");
            let mut greeting = [0; 3];
            stream
                .read_exact(&mut greeting)
                .await
                .expect("SOCKS greeting should arrive");
            assert_eq!(greeting, [5, 1, 0]);
            stream
                .write_all(&[5, 0])
                .await
                .expect("SOCKS greeting should be accepted");

            let mut request = [0; 4];
            stream
                .read_exact(&mut request)
                .await
                .expect("SOCKS connect request should arrive");
            assert_eq!(request, [5, 1, 0, 1]);
            let mut target = [0; 6];
            stream
                .read_exact(&mut target)
                .await
                .expect("SOCKS target address should arrive");
            target_ip_tx
                .send([target[0], target[1], target[2], target[3]])
                .expect("test should still be waiting for the target address");
            stream
                .write_all(&[5, 0, 0, 1, 127, 0, 0, 1, 0, 0])
                .await
                .expect("SOCKS connect should be accepted");
        });

        let client = transfer_client(
            "example.com",
            IpAddr::V4(Ipv4Addr::new(203, 0, 113, 9)),
            &proxy_url,
            1_000,
        )
        .expect("transfer client should build");
        let _ = client.get("https://example.com/").send().await;
        server.await.expect("SOCKS server task should finish");

        assert_eq!(
            target_ip_rx
                .await
                .expect("SOCKS target IP should be reported"),
            [203, 0, 113, 9]
        );
    }
}
