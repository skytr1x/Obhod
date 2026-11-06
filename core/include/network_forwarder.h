#ifndef NETWORK_FORWARDER_H
#define NETWORK_FORWARDER_H

#include <string>
#include <cstdint>
#include <vector>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <android/log.h>

#define LOG_TAG_FORWARDER "NetworkForwarder"
#define LOGD_FORWARDER(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG_FORWARDER, __VA_ARGS__)
#define LOGE_FORWARDER(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG_FORWARDER, __VA_ARGS__)

namespace skyunblock {

// creates a plain tcp connection to a destination host/port
class NetworkForwarder {
public:
    NetworkForwarder() : m_socket(-1) {}

    ~NetworkForwarder() { close(); }

    bool connect(const std::string& host, uint16_t port) {
        close();

        m_socket = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        if (m_socket < 0) {
            LOGE_FORWARDER("socket() failed: %s", strerror(errno));
            return false;
        }

        struct sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_port   = htons(port);

        if (::inet_pton(AF_INET, host.c_str(), &addr.sin_addr) != 1) {
            LOGE_FORWARDER("inet_pton failed for host '%s': %s", host.c_str(), strerror(errno));
            close();
            return false;
        }

        if (::connect(m_socket, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) < 0) {
            LOGE_FORWARDER("connect() to %s:%u failed: %s", host.c_str(), port, strerror(errno));
            close();
            return false;
        }

        LOGD_FORWARDER("Connected to %s:%u (fd=%d)", host.c_str(), port, m_socket);
        return true;
    }

    bool send(const uint8_t* data, size_t len) {
        if (m_socket < 0 || !data || len == 0) return false;

        setTcpNodelay(true);

        size_t sent = 0;
        while (sent < len) {
            ssize_t n = ::send(m_socket, data + sent, len - sent, MSG_NOSIGNAL);
            if (n < 0) {
                LOGE_FORWARDER("send() failed: %s", strerror(errno));
                return false;
            }
            sent += static_cast<size_t>(n);
        }
        return true;
    }

    ssize_t recv(uint8_t* buf, size_t bufLen) {
        if (m_socket < 0 || !buf || bufLen == 0) return -1;
        return ::recv(m_socket, buf, bufLen, 0);
    }

    int fd() const { return m_socket; }

    void close() {
        if (m_socket >= 0) {
            ::close(m_socket);
            m_socket = -1;
        }
    }

    void setTcpNodelay(bool enable) {
        if (m_socket < 0) return;
        int flag = enable ? 1 : 0;
        ::setsockopt(m_socket, IPPROTO_TCP, TCP_NODELAY, &flag, sizeof(flag));
    }

private:
    int m_socket;
};

}

#endif
