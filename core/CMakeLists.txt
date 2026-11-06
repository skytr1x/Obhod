cmake_minimum_required(VERSION 3.22)
project(skycore CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

include_directories(include)

set(HEV_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/hev-socks5-tunnel")

if(EXISTS "${HEV_DIR}/CMakeLists.txt" OR EXISTS "${HEV_DIR}/Makefile")
    message(STATUS "hev-socks5-tunnel found at ${HEV_DIR} — building as ExternalProject")
    include(ExternalProject)

    set(HEV_BUILD_DIR "${CMAKE_CURRENT_BINARY_DIR}/hev-build/${ANDROID_ABI}")
    set(HEV_LIB "${HEV_BUILD_DIR}/bin/libhev-socks5-tunnel.a")
    set(HEV_YAML_LIB "${HEV_BUILD_DIR}/third-part/yaml/bin/libyaml.a")
    set(HEV_LWIP_LIB "${HEV_BUILD_DIR}/third-part/lwip/bin/liblwip.a")
    set(HEV_TASK_LIB "${HEV_BUILD_DIR}/third-part/hev-task-system/bin/libhev-task-system.a")

    ExternalProject_Add(
        hev_socks5_tunnel_ext
        SOURCE_DIR      "${HEV_DIR}"
        CONFIGURE_COMMAND ""
        BUILD_COMMAND
            sh -c "make clean -C ${HEV_DIR} && make static -C ${HEV_DIR} CC=${CMAKE_C_COMPILER} AR=${CMAKE_AR} STRIP=${CMAKE_STRIP} \"CFLAGS=${CMAKE_C_FLAGS} --target=${CMAKE_C_COMPILER_TARGET} -fPIC\" && mkdir -p ${HEV_BUILD_DIR}/bin && cp ${HEV_DIR}/bin/*.a ${HEV_BUILD_DIR}/bin/ && mkdir -p ${HEV_BUILD_DIR}/third-part/yaml/bin && cp ${HEV_DIR}/third-part/yaml/bin/*.a ${HEV_BUILD_DIR}/third-part/yaml/bin/ && mkdir -p ${HEV_BUILD_DIR}/third-part/lwip/bin && cp ${HEV_DIR}/third-part/lwip/bin/*.a ${HEV_BUILD_DIR}/third-part/lwip/bin/ && mkdir -p ${HEV_BUILD_DIR}/third-part/hev-task-system/bin && cp ${HEV_DIR}/third-part/hev-task-system/bin/*.a ${HEV_BUILD_DIR}/third-part/hev-task-system/bin/"
        BUILD_IN_SOURCE 1
        INSTALL_COMMAND ""
        BUILD_BYPRODUCTS "${HEV_LIB}" "${HEV_YAML_LIB}" "${HEV_LWIP_LIB}" "${HEV_TASK_LIB}"
    )

    add_library(hev_socks5_tunnel_static STATIC IMPORTED)
    set_target_properties(hev_socks5_tunnel_static PROPERTIES
        IMPORTED_LOCATION "${HEV_LIB}"
    )
    add_dependencies(hev_socks5_tunnel_static hev_socks5_tunnel_ext)

    set(HEV_AVAILABLE TRUE)
    set(HEV_INCLUDE_DIR "${HEV_DIR}/include")
else()
    message(STATUS "hev-socks5-tunnel NOT found — building stub (tun2socks_bridge in stub mode)")
    set(HEV_AVAILABLE FALSE)
endif()

# main native directory
set(SOURCES
    src/skycore.cpp
    src/jni/native_bridge.cpp
    src/jni/tun2socks_bridge.cpp
    src/socks/socks5_server.cpp
    src/desync/desync_engine.cpp
    src/desync/tls_utils.cpp
    src/probe/prober.cpp
    src/strategy/fake_payloads.cpp
    src/strategy/profile.cpp
    src/strategy/strategy_selector.cpp
    src/util/host_list.cpp
    src/util/json.cpp
)

add_library(skycore SHARED ${SOURCES})

target_compile_definitions(skycore PRIVATE _GNU_SOURCE=1)

target_compile_options(skycore PRIVATE
    -fvisibility=hidden
    -Wall
    -Wextra
    -Wno-unused-parameter
)

if(HEV_AVAILABLE)
    target_compile_definitions(skycore PRIVATE HEV_SOCKS5_TUNNEL_AVAILABLE=1)
    target_include_directories(skycore PRIVATE "${HEV_INCLUDE_DIR}")
    add_dependencies(skycore hev_socks5_tunnel_ext)
    target_link_libraries(skycore
        hev_socks5_tunnel_static
        "${HEV_YAML_LIB}"
        "${HEV_LWIP_LIB}"
        "${HEV_TASK_LIB}"
        log
        android
    )
else()
    target_link_libraries(skycore log android)
endif()
