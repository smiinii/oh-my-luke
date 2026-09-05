#!/bin/sh
set -eu
original_path=${PATH:-}
PATH=/usr/bin:/bin
export PATH

product_marker="io.ohmyluke"

fail() {
    printf '%s\n' "OML 설치 오류: $*" >&2
    exit 1
}

usage() {
    cat <<'EOF'
사용법: ./install.sh [--prefix 절대경로]

기본 설치 위치는 $HOME/.local입니다.
EOF
}

validate_prefix() {
    case "$prefix" in
        /*) ;;
        *) fail "--prefix는 절대경로여야 합니다." ;;
    esac
    case "$prefix" in
        /) fail "운영체제 핵심 경로에는 설치할 수 없습니다." ;;
        */|*//*) fail "--prefix에 끝 슬래시나 반복 슬래시를 사용할 수 없습니다." ;;
    esac
    case "$prefix/" in
        */../*|*/./*) fail "--prefix에 . 또는 .. 경로 조각을 사용할 수 없습니다." ;;
    esac
    case "$prefix" in
        /bin|/bin/*|/boot|/boot/*|/dev|/dev/*|/etc|/etc/*|/proc|/proc/*|/sbin|/sbin/*|/sys|/sys/*|/usr|/usr/*)
            fail "운영체제 핵심 경로에는 설치할 수 없습니다."
            ;;
        /System|/System/*|/private/etc|/private/etc/*|/private/var/root|/private/var/root/*)
            fail "운영체제 핵심 경로에는 설치할 수 없습니다."
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

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
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
reject_symlink_components "$prefix"

version_file="$script_dir/VERSION"
platform_file="$script_dir/PLATFORM"
[ -f "$version_file" ] && [ ! -L "$version_file" ] || fail "배포 묶음의 VERSION 파일이 올바르지 않습니다."
[ -f "$platform_file" ] && [ ! -L "$platform_file" ] || fail "배포 묶음의 PLATFORM 파일이 올바르지 않습니다."
source_uninstaller="$script_dir/uninstall.sh"
[ ! -L "$source_uninstaller" ] && [ -f "$source_uninstaller" ] \
    || fail "배포 묶음의 제거 스크립트가 올바르지 않습니다."
version=$(sed -n 's/^version=//p' "$version_file")
bundle_os=$(sed -n 's/^os=//p' "$platform_file")
bundle_arch=$(sed -n 's/^arch=//p' "$platform_file")
printf '%s\n' "$version" \
    | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z]+([.-][0-9A-Za-z]+)*)?$' \
    || fail "VERSION 값이 올바르지 않습니다."
case "$bundle_os:$bundle_arch" in
    macos:aarch64|linux:x64) ;;
    *) fail "PLATFORM 값이 올바르지 않습니다." ;;
esac
case "$(uname -s)" in
    Darwin) host_os=macos ;;
    Linux) host_os=linux ;;
    *) fail "지원하지 않는 운영체제입니다: $(uname -s)" ;;
esac
case "$(uname -m)" in
    arm64|aarch64) host_arch=aarch64 ;;
    x86_64|amd64) host_arch=x64 ;;
    *) fail "지원하지 않는 CPU 아키텍처입니다: $(uname -m)" ;;
esac
[ "$bundle_os:$bundle_arch" = "$host_os:$host_arch" ] \
    || fail "이 묶음은 $bundle_os-$bundle_arch 용이며 현재 환경은 $host_os-$host_arch 입니다."

if [ -d "$script_dir/omluke.app" ] && [ ! -L "$script_dir/omluke.app" ]; then
    payload_name="omluke.app"
    launcher_suffix="omluke.app/Contents/MacOS/omluke"
elif [ -d "$script_dir/omluke" ] && [ ! -L "$script_dir/omluke" ]; then
    payload_name="omluke"
    launcher_suffix="omluke/bin/omluke"
else
    fail "운영체제용 OML 앱 이미지가 없습니다."
fi

install_root="$prefix/lib/omluke"
versions_root="$install_root/versions"
version_dir="$versions_root/$version"
marker="$install_root/.owned-by-omluke"
bin_dir="$prefix/bin"
bin_link="$bin_dir/omluke"
installed_launcher="$version_dir/$launcher_suffix"
lock_file="$prefix/lib/.omluke-operation.lock"
initial_bin_state=absent
initial_bin_target=
initial_uninstaller_state=absent
initial_uninstaller_inode=
staging=
temporary_marker=
temporary_uninstaller=
temporary_link=
temporary_install_root=

acquire_operation_lock() {
    [ ! -L "$lock_file" ] || fail "수명주기 잠금 파일이 심볼릭 링크라서 중단했습니다: $lock_file"
    if [ ! -e "$lock_file" ]; then
        (umask 077; set -C; : > "$lock_file") 2>/dev/null || :
    fi
    [ ! -L "$lock_file" ] && [ -f "$lock_file" ] \
        || fail "수명주기 잠금 경로가 일반 파일이 아닙니다: $lock_file"
    exec 9<> "$lock_file" || fail "수명주기 잠금 파일을 열 수 없습니다: $lock_file"
    lock_path_inode=$(ls -di "$lock_file" | awk '{print $1}')
    lock_fd_inode=$(ls -diL /dev/fd/9 | awk '{print $1}')
    [ ! -L "$lock_file" ] && [ -f "$lock_file" ] && [ "$lock_path_inode" = "$lock_fd_inode" ] \
        || fail "수명주기 잠금 파일이 여는 동안 변경되었습니다: $lock_file"
    case "$host_os" in
        macos)
            [ -x /usr/bin/lockf ] || fail "운영체제 잠금 도구를 찾을 수 없습니다: /usr/bin/lockf"
            /usr/bin/lockf -s -t 0 9 \
                || fail "다른 OML 설치 또는 제거 작업이 진행 중입니다: $lock_file"
            ;;
        linux)
            [ -x /usr/bin/flock ] || fail "운영체제 잠금 도구를 찾을 수 없습니다: /usr/bin/flock"
            /usr/bin/flock -n 9 \
                || fail "다른 OML 설치 또는 제거 작업이 진행 중입니다: $lock_file"
            ;;
        *) fail "지원하지 않는 운영체제 잠금 방식입니다: $host_os" ;;
    esac
    final_lock_path_inode=$(ls -di "$lock_file" | awk '{print $1}')
    final_lock_fd_inode=$(ls -diL /dev/fd/9 | awk '{print $1}')
    [ ! -L "$lock_file" ] && [ -f "$lock_file" ] \
        && [ "$final_lock_path_inode" = "$lock_path_inode" ] \
        && [ "$final_lock_fd_inode" = "$lock_fd_inode" ] \
        || fail "수명주기 잠금 파일이 획득 중 변경되었습니다: $lock_file"
}

cleanup() {
    if [ -n "$staging" ] && [ -d "$staging" ]; then
        rm -rf -- "$staging" || :
        staging=
    fi
    if [ -n "$temporary_marker" ]; then
        rm -f -- "$temporary_marker" || :
        temporary_marker=
    fi
    if [ -n "$temporary_uninstaller" ]; then
        rm -f -- "$temporary_uninstaller" || :
        temporary_uninstaller=
    fi
    if [ -n "$temporary_link" ]; then
        rm -f -- "$temporary_link" || :
        temporary_link=
    fi
    if [ -n "$temporary_install_root" ] && [ -d "$temporary_install_root" ]; then
        rm -rf -- "$temporary_install_root" || :
        temporary_install_root=
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

reject_symlink_components "$prefix/lib"
reject_symlink_components "$bin_dir"
[ ! -e "$prefix" ] || [ -d "$prefix" ] || fail "설치 prefix가 디렉터리가 아닙니다: $prefix"
mkdir -p "$prefix/lib" "$bin_dir"
reject_symlink_components "$prefix/lib"
reject_symlink_components "$bin_dir"
acquire_operation_lock

if [ -e "$bin_link" ] || [ -L "$bin_link" ]; then
    [ -L "$bin_link" ] || fail "기존 명령을 덮어쓰지 않습니다: $bin_link"
    [ ! -d "$bin_link" ] || fail "디렉터리를 가리키는 명령 링크를 덮어쓰지 않습니다: $bin_link"
    current_target=$(readlink "$bin_link")
    is_owned_command_target "$current_target" \
        || fail "OML이 소유하지 않은 심볼릭 링크를 덮어쓰지 않습니다: $bin_link"
    initial_bin_state=owned-link
    initial_bin_target=$current_target
fi

reject_symlink_components "$install_root"
reject_symlink_components "$versions_root"
if [ -e "$install_root" ]; then
    [ -d "$install_root" ] || fail "기존 설치 경로가 디렉터리가 아닙니다: $install_root"
    [ ! -L "$marker" ] && [ -f "$marker" ] \
        || fail "OML 소유 표시가 없는 기존 설치 경로입니다: $install_root"
    [ "$(sed -n '1p' "$marker")" = "$product_marker" ] || fail "기존 설치 경로의 소유 표시가 다릅니다."
else
    temporary_install_root=$(mktemp -d "$prefix/lib/.omluke-install-root.XXXXXXXX") \
        || fail "임시 설치 루트를 만들 수 없습니다."
    chmod 755 "$temporary_install_root"
    marker_candidate="$temporary_install_root/.owned-by-omluke-$$"
    [ ! -e "$marker_candidate" ] && [ ! -L "$marker_candidate" ] \
        || fail "임시 소유 표시 경로가 이미 존재합니다: $marker_candidate"
    (umask 077; set -C; printf '%s\n' "$product_marker" > "$marker_candidate") \
        || fail "임시 소유 표시를 만들 수 없습니다."
    temporary_marker=$marker_candidate
    chmod 644 "$temporary_marker"
    mv "$temporary_marker" "$temporary_install_root/.owned-by-omluke"
    temporary_marker=
    [ ! -e "$install_root" ] && [ ! -L "$install_root" ] \
        || fail "설치 루트가 초기화 중 변경되었습니다: $install_root"
    mv "$temporary_install_root" "$install_root"
    temporary_install_root=
    [ ! -L "$marker" ] && [ -f "$marker" ] \
        && [ "$(sed -n '1p' "$marker")" = "$product_marker" ] \
        || fail "완성된 소유 표시와 함께 설치 루트를 게시하지 못했습니다: $install_root"
fi

installed_uninstaller="$install_root/uninstall.sh"
[ ! -L "$installed_uninstaller" ] || fail "설치된 제거 스크립트가 심볼릭 링크라서 중단했습니다."
[ ! -d "$installed_uninstaller" ] || fail "설치된 제거 스크립트 경로가 디렉터리입니다."
if [ -e "$installed_uninstaller" ]; then
    [ -f "$installed_uninstaller" ] || fail "설치된 제거 스크립트가 일반 파일이 아닙니다."
    initial_uninstaller_state=regular
    initial_uninstaller_inode=$(ls -di "$installed_uninstaller" | awk '{print $1}')
fi

mkdir -p "$versions_root"
reject_symlink_components "$versions_root"
if [ -e "$version_dir" ] || [ -L "$version_dir" ]; then
    [ ! -L "$version_dir" ] || fail "버전 설치 경로가 심볼릭 링크라서 중단했습니다."
    [ -d "$version_dir" ] || fail "버전 설치 경로가 디렉터리가 아닙니다: $version_dir"
    [ ! -L "$version_dir/VERSION" ] && [ -f "$version_dir/VERSION" ] \
        || fail "기존 버전 설치가 불완전합니다: $version_dir"
    [ "$(sed -n 's/^version=//p' "$version_dir/VERSION")" = "$version" ] \
        || fail "기존 버전 정보가 일치하지 않습니다."
    reject_symlink_components "$installed_launcher"
    [ ! -L "$installed_launcher" ] && [ -x "$installed_launcher" ] \
        || fail "기존 버전 실행 파일이 없습니다: $installed_launcher"
else
    staging_candidate="$versions_root/.install-$version-$$"
    [ ! -e "$staging_candidate" ] && [ ! -L "$staging_candidate" ] \
        || fail "임시 설치 경로가 이미 존재합니다: $staging_candidate"
    mkdir "$staging_candidate"
    staging=$staging_candidate
    cp -R "$script_dir/$payload_name" "$staging/$payload_name"
    cp "$version_file" "$staging/VERSION"
    reject_symlink_components "$staging/$launcher_suffix"
    [ ! -L "$staging/$launcher_suffix" ] && [ -x "$staging/$launcher_suffix" ] \
        || fail "복사한 실행 파일의 권한이 올바르지 않습니다."
    mv "$staging" "$version_dir"
    staging=
fi

uninstaller_candidate="$install_root/.uninstall-$$"
[ ! -e "$uninstaller_candidate" ] && [ ! -L "$uninstaller_candidate" ] \
    || fail "임시 제거 스크립트 경로가 이미 존재합니다: $uninstaller_candidate"
(umask 022; set -C; : > "$uninstaller_candidate") || fail "임시 제거 스크립트를 만들 수 없습니다."
temporary_uninstaller=$uninstaller_candidate
cp "$source_uninstaller" "$temporary_uninstaller"
chmod 755 "$temporary_uninstaller"
case "$initial_uninstaller_state" in
    absent)
        [ ! -e "$installed_uninstaller" ] && [ ! -L "$installed_uninstaller" ] \
            || fail "제거 스크립트 경로가 설치 중 변경되었습니다: $installed_uninstaller"
        ln "$temporary_uninstaller" "$installed_uninstaller" \
            || fail "제거 스크립트 경로가 설치 중 변경되었습니다: $installed_uninstaller"
        rm -- "$temporary_uninstaller"
        temporary_uninstaller=
        ;;
    regular)
        [ ! -L "$installed_uninstaller" ] && [ -f "$installed_uninstaller" ] \
            && [ ! -d "$installed_uninstaller" ] \
            || fail "기존 제거 스크립트가 설치 중 변경되었습니다: $installed_uninstaller"
        final_uninstaller_inode=$(ls -di "$installed_uninstaller" | awk '{print $1}')
        [ "$final_uninstaller_inode" = "$initial_uninstaller_inode" ] \
            || fail "기존 제거 스크립트가 설치 중 교체되었습니다: $installed_uninstaller"
        mv -f "$temporary_uninstaller" "$installed_uninstaller"
        temporary_uninstaller=
        ;;
    *) fail "알 수 없는 제거 스크립트 상태입니다." ;;
esac

case "$initial_bin_state" in
    absent)
        [ ! -e "$bin_link" ] && [ ! -L "$bin_link" ] \
            || fail "명령 경로가 설치 중 변경되었습니다: $bin_link"
        ln -s "$installed_launcher" "$bin_link" \
            || fail "명령 경로가 설치 중 변경되었습니다: $bin_link"
        ;;
    owned-link)
        link_candidate="$bin_dir/.omluke-link-$$"
        [ ! -e "$link_candidate" ] && [ ! -L "$link_candidate" ] \
            || fail "임시 명령 경로가 이미 존재합니다: $link_candidate"
        ln -s "$installed_launcher" "$link_candidate"
        temporary_link=$link_candidate
        [ -L "$bin_link" ] && [ ! -d "$bin_link" ] \
            || fail "기존 OML 명령이 설치 중 변경되었습니다: $bin_link"
        final_target=$(readlink "$bin_link")
        [ "$final_target" = "$initial_bin_target" ] && is_owned_command_target "$final_target" \
            || fail "기존 OML 명령 대상이 설치 중 변경되었습니다: $bin_link"
        mv -f "$temporary_link" "$bin_link"
        temporary_link=
        ;;
    *) fail "알 수 없는 명령 경로 상태입니다." ;;
esac

cleanup
exec 9>&-
trap - EXIT HUP INT TERM
printf '%s\n' "OML $version 설치 완료: $bin_link"
case ":$original_path:" in
    *:"$bin_dir":*) ;;
    *) printf '%s\n' "PATH에 $bin_dir 를 추가한 뒤 omluke --help를 실행하세요." ;;
esac
