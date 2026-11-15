#!/usr/bin/env bash
# Host facts in one place, so every script reports them the same way on macOS and Linux.
# Sourced, not run. Each function prints one value and never fails the caller.

host_os() {
    if command -v sw_vers >/dev/null 2>&1; then
        echo "$(sw_vers -productName) $(sw_vers -productVersion) $(uname -m)"
    elif [[ -r /etc/os-release ]]; then
        echo "$(. /etc/os-release && echo "$PRETTY_NAME") $(uname -m)"
    else
        uname -sm
    fi
}

host_cpus() {
    getconf _NPROCESSORS_ONLN 2>/dev/null || echo unknown
}

host_cpu_model() {
    if command -v sysctl >/dev/null 2>&1 && sysctl -n machdep.cpu.brand_string >/dev/null 2>&1; then
        sysctl -n machdep.cpu.brand_string
    else
        awk -F': ' '/model name/ {print $2; exit}' /proc/cpuinfo 2>/dev/null || echo unknown
    fi
}

host_memory_gb() {
    if [[ -r /proc/meminfo ]]; then
        awk '/MemTotal/ {print int($2 / 1048576)}' /proc/meminfo
    else
        echo $(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1073741824 ))
    fi
}

# Prints the date that is $2 days after the YYYY-MM-DD date $1. BSD and GNU date disagree on the
# flags for this, so it goes through python3, which both platforms already need.
date_plus_days() {
    python3 -c 'import datetime, sys; print(datetime.date.fromisoformat(sys.argv[1]) + datetime.timedelta(days=int(sys.argv[2])))' "$1" "$2"
}

# Prints an epoch second as HH:MM:SS in UTC.
utc_clock() {
    python3 -c 'import datetime, sys; print(datetime.datetime.fromtimestamp(int(sys.argv[1]), datetime.timezone.utc).strftime("%H:%M:%S"))' "$1"
}
