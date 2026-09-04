#!/bin/sh
set -eu
PATH=/usr/bin:/bin
export PATH

product_marker="io.ohmyluke"

fail() {
    printf '%s\n' "OML 제거 오류: $*" >&2
    exit 1
}

usage() {
    cat <<'EOF'
사용법: uninstall.sh [--prefix 절대경로]

OML 프로그램만 제거합니다. 프로젝트의 .oml 기록과 Codex CLI 설정은 유지합니다.
EOF
}

validate_prefix() {
    case "$prefix" in
        /*) ;;
        *) fail "--prefix는 절대경로여야 합니다." ;;
    esac
    case "$prefix" in
        /) fail "운영체제 핵심 경로를 제거 대상으로 사용할 수 없습니다." ;;
        */|*//*) fail "--prefix에 끝 슬래시나 반복 슬래시를 사용할 수 없습니다." ;;
    esac
    case "$prefix/" in
        */../*|*/./*) fail "--prefix에 . 또는 .. 경로 조각을 사용할 수 없습니다." ;;
    esac
    case "$prefix" in
        /bin|/bin/*|/boot|/boot/*|/dev|/dev/*|/etc|/etc/*|/proc|/proc/*|/sbin|/sbin/*|/sys|/sys/*|/usr|/usr/*)
            fail "운영체제 핵심 경로를 제거 대상으로 사용할 수 없습니다."
            ;;
        /System|/System/*|/private/etc|/private/etc/*|/private/var/root|/private/var/root/*)
            fail "운영체제 핵심 경로를 제거 대상으로 사용할 수 없습니다."
            ;;
    esac
}

reject_symlink_components() {
    checked_path=$1
    while [ "$checked_path" != "/" ]; do
        [ ! -L "$checked_path" ] || fail "심볼릭 링크가 포함된 경로는 사용할 수 없습니다: $checked_path"
        parent_path=${checked_path%/*}
        if [ -n "$parent_path" ]; then
            checked_path=$parent_path
        else
            checked_path=/
        fi
    done
}

is_owned_command_target() {
    command_target=$1
    case "$command_target" in
        */|*//*) return 1 ;;
    esac
    case "$command_target/" in
        */../*|*/./*) return 1 ;;
    esac
    case "$command_target" in
        "$install_root"/versions/*) ;;
        *) return 1 ;;
    esac
    relative_target=${command_target#"$install_root/versions/"}
    link_version=${relative_target%%/*}
    [ -n "$link_version" ] && [ "$link_version" != "$relative_target" ] || return 1
    printf '%s\n' "$link_version" \
        | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z]+([.-][0-9A-Za-z]+)*)?$' \
        || return 1
    case "$relative_target" in
        "$link_version/omluke/bin/omluke"|"$link_version/omluke.app/Contents/MacOS/omluke") return 0 ;;
        *) return 1 ;;
    esac
}

prefix=${OMLUKE_PREFIX:-"${HOME:?HOME is required}/.local"}
while [ "$#" -gt 0 ]; do
    case "$1" in
        --prefix)
            [ "$#" -ge 2 ] || fail "--prefix 뒤에 경로가 필요합니다."
            prefix=$2
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            fail "알 수 없는 인자: $1"
            ;;
    esac
done

validate_prefix
install_root="$prefix/lib/omluke"
marker="$install_root/.owned-by-omluke"
bin_link="$prefix/bin/omluke"
lock_dir="$prefix/lib/.omluke-operation-lock"
lock_held=false

cleanup() {
    if [ "$lock_held" = true ]; then
        if rmdir "$lock_dir" 2>/dev/null; then
            lock_held=false
        fi
    fi
}

handle_signal() {
    signal_status=$1
    trap - EXIT HUP INT TERM
    cleanup
    exit "$signal_status"
}

trap cleanup EXIT
trap 'handle_signal 129' HUP
trap 'handle_signal 130' INT
trap 'handle_signal 143' TERM

reject_symlink_components "$prefix"
reject_symlink_components "$prefix/lib"
reject_symlink_components "$prefix/bin"
reject_symlink_components "$install_root"
[ -d "$install_root" ] || fail "OML 설치를 찾을 수 없습니다: $install_root"
[ ! -L "$marker" ] && [ -f "$marker" ] || fail "OML 소유 표시가 없어 제거하지 않습니다: $install_root"
[ "$(sed -n '1p' "$marker")" = "$product_marker" ] || fail "설치 경로의 소유 표시가 다릅니다."

mkdir "$lock_dir" 2>/dev/null || fail "다른 OML 설치 또는 제거 작업이 진행 중입니다: $lock_dir"
lock_held=true
reject_symlink_components "$install_root"
[ ! -L "$marker" ] && [ -f "$marker" ] || fail "OML 소유 표시가 없어 제거하지 않습니다: $install_root"
[ "$(sed -n '1p' "$marker")" = "$product_marker" ] || fail "설치 경로의 소유 표시가 다릅니다."

if [ -e "$bin_link" ] || [ -L "$bin_link" ]; then
    if [ -L "$bin_link" ]; then
        current_target=$(readlink "$bin_link")
        if [ ! -d "$bin_link" ] && is_owned_command_target "$current_target"; then
            rm -- "$bin_link"
        else
            printf '%s\n' "다른 대상을 가리키는 명령은 유지했습니다: $bin_link" >&2
        fi
    else
        printf '%s\n' "일반 파일인 명령은 유지했습니다: $bin_link" >&2
    fi
fi

rm -rf -- "$install_root"
cleanup
trap - EXIT HUP INT TERM
printf '%s\n' "OML 프로그램을 제거했습니다. 프로젝트의 .oml 기록과 Codex CLI 설정은 유지했습니다."
