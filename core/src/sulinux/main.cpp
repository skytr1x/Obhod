#include <cerrno>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <fstream>
#include <iterator>
#include <memory>
#include <string>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#include "profile.h"
#include "sky_log.h"
#include "socks5_server.h"
#include "strategy_selector.h"

namespace {

volatile std::sig_atomic_t g_stop = 0;

void onStopSignal(int) {
    g_stop = 1;
}

void usage() {
    std::fprintf(stderr,
                 "Usage: sulinux [--port N] [--profile FILE] [--cache DIR] "
                 "[--pid-file FILE] [--log FILE] [--daemon]\n");
}

bool readFile(const std::string& path, std::string& text) {
    std::ifstream input(path, std::ios::binary);
    if (!input) return false;
    text.assign(std::istreambuf_iterator<char>(input),
                std::istreambuf_iterator<char>());
    return true;
}

void writePidFile(const std::string& path) {
    if (path.empty()) return;
    std::ofstream output(path, std::ios::trunc);
    if (output) output << static_cast<long>(::getpid()) << '\n';
}

// detaches from the caller so the daemon survives the short-lived su or
// kernelSU installer shell that launched it
bool daemonize(const std::string& logPath) {
    pid_t pid = ::fork();
    if (pid < 0) return false;
    if (pid > 0) ::_exit(0);

    if (::setsid() < 0) return false;

    pid = ::fork();
    if (pid < 0) return false;
    if (pid > 0) ::_exit(0);

    ::umask(0);
    const int devNull = ::open("/dev/null", O_RDONLY);
    if (devNull >= 0) {
        ::dup2(devNull, STDIN_FILENO);
        ::close(devNull);
    }
    const int out = logPath.empty()
        ? ::open("/dev/null", O_WRONLY)
        : ::open(logPath.c_str(), O_WRONLY | O_CREAT | O_APPEND, 0600);
    if (out >= 0) {
        ::dup2(out, STDOUT_FILENO);
        ::dup2(out, STDERR_FILENO);
        if (out > STDERR_FILENO) ::close(out);
    }
    return true;
}

}

int main(int argc, char** argv) {
    uint16_t port = 10800;
    std::string profilePath;
    std::string cachePath;
    std::string pidFile;
    std::string logPath;
    bool daemon = false;

    for (int i = 1; i < argc; ++i) {
        const std::string arg = argv[i];
        auto value = [&](const char* name) -> const char* {
            if (i + 1 >= argc) {
                std::fprintf(stderr, "sulinux: missing value for %s\n", name);
                std::exit(2);
            }
            return argv[++i];
        };
        if (arg == "--port") {
            const int parsed = std::atoi(value("--port"));
            if (parsed <= 0 || parsed > 65535) {
                std::fprintf(stderr, "sulinux: invalid port\n");
                return 2;
            }
            port = static_cast<uint16_t>(parsed);
        } else if (arg == "--profile") {
            profilePath = value("--profile");
        } else if (arg == "--cache") {
            cachePath = value("--cache");
        } else if (arg == "--pid-file") {
            pidFile = value("--pid-file");
        } else if (arg == "--log") {
            logPath = value("--log");
        } else if (arg == "--daemon") {
            daemon = true;
        } else if (arg == "--help" || arg == "-h") {
            usage();
            return 0;
        } else {
            std::fprintf(stderr, "sulinux: unknown argument: %s\n", arg.c_str());
            usage();
            return 2;
        }
    }

    if (daemon && !daemonize(logPath)) {
        std::fprintf(stderr, "sulinux: could not daemonize: %s\n", std::strerror(errno));
        return 1;
    }

    std::shared_ptr<skyunblock::Profile> profile;
    std::string profileText;
    if (!profilePath.empty() && readFile(profilePath, profileText)) {
        std::string error;
        profile = skyunblock::parseProfile(profileText, error);
        if (!profile) {
            SKY_LOGE("SULinux: profile %s is invalid: %s; using built-in profile",
                     profilePath.c_str(), error.c_str());
        }
    } else if (!profilePath.empty()) {
        SKY_LOGW("SULinux: cannot read profile %s; using built-in profile",
                 profilePath.c_str());
    }
    if (!profile) profile = skyunblock::defaultProfile();

    auto selector = std::make_shared<skyunblock::StrategySelector>(profile);
    if (!cachePath.empty()) {
        ::mkdir(cachePath.c_str(), 0700);
        selector->setCachePath(cachePath + "/strategy");
    }

    skyunblock::Socks5Server server;
    const auto event = [](const std::string& message) {
        SKY_LOGI("%s", message.c_str());
    };

    if (!server.start(port, selector.get(), nullptr, event, nullptr, true)) {
        SKY_LOGE("SULinux: failed to start transparent proxy on port %u", port);
        return 3;
    }

    writePidFile(pidFile);
    std::signal(SIGTERM, onStopSignal);
    std::signal(SIGINT, onStopSignal);
    std::signal(SIGHUP, onStopSignal);

    SKY_LOGI("SULinux: transparent proxy ready on 127.0.0.1:%u and [::1]:%u "
             "(profile: %s, pid: %ld)",
             port, port,
             profile->updated.empty() ? "built-in" : profile->updated.c_str(),
             static_cast<long>(::getpid()));

    while (!g_stop) {
        ::sleep(1);
    }

    SKY_LOGI("SULinux: stopping");
    server.stop();
    if (!pidFile.empty()) ::unlink(pidFile.c_str());
    return 0;
}
