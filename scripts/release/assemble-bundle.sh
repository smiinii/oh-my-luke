#!/bin/sh
set -eu

fail() {
    printf '%s\n' "RC 묶음 생성 오류: $*" >&2
    exit 1
}

[ "$#" -eq 3 ] || fail "사용법: assemble-bundle.sh VERSION INPUT_DIR OUTPUT_DIR"
version=$1
input_dir=$2
output_dir=$3

printf '%s\n' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+-rc\.[0-9]+$' \
    || fail "버전은 MAJOR.MINOR.PATCH-rc.NUMBER 형식이어야 합니다."
[ -d "$input_dir" ] || fail "입력 디렉터리를 찾을 수 없습니다: $input_dir"
if [ -e "$output_dir" ]; then
    [ ! -L "$output_dir" ] || fail "출력 경로가 심볼릭 링크입니다: $output_dir"
    [ -d "$output_dir" ] || fail "출력 경로가 디렉터리가 아닙니다: $output_dir"
    [ -z "$(find "$output_dir" -mindepth 1 -maxdepth 1 -print -quit)" ] || fail "출력 디렉터리는 비어 있어야 합니다."
fi

hash_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        fail "SHA-256 도구를 찾을 수 없습니다."
    fi
}

validate_asset() {
    classifier=$1
    expected_os=$2
    expected_arch=$3
    archive_name="omluke-$version-$classifier.tar.gz"
    archive="$input_dir/$archive_name"
    checksum="$archive.sha256"
    evidence="$input_dir/evidence/omluke-$version-$classifier.json"

    [ ! -L "$archive" ] && [ -f "$archive" ] || fail "배포 파일이 없습니다: $archive_name"
    [ ! -L "$checksum" ] && [ -f "$checksum" ] || fail "체크섬 파일이 없습니다: $archive_name.sha256"
    [ ! -L "$evidence" ] && [ -f "$evidence" ] || fail "검증 근거가 없습니다: $(basename "$evidence")"

    [ "$(awk 'END { print NR }' "$checksum")" -eq 1 ] || fail "체크섬 파일은 정확히 한 줄이어야 합니다: $archive_name"
    expected_line=$(sed -n '1p' "$checksum")
    set -- $expected_line
    [ "$#" -eq 2 ] || fail "체크섬 형식이 올바르지 않습니다: $archive_name"
    expected_hash=$1
    expected_name=$2
    [ "$expected_name" = "$archive_name" ] || fail "체크섬 파일명이 일치하지 않습니다: $archive_name"
    actual_hash=$(hash_file "$archive")
    [ "$expected_hash" = "$actual_hash" ] || fail "체크섬이 일치하지 않습니다: $archive_name"

    jq -e \
        --arg version "$version" \
        --arg os "$expected_os" \
        --arg arch "$expected_arch" \
        --arg hash "$actual_hash" \
        '.productVersion == $version
          and .os == $os
          and .arch == $arch
          and .sha256 == $hash
          and .ranWithEmptyPath == true
          and .bundledJavaVerified == true' \
        "$evidence" >/dev/null || fail "검증 근거가 배포 파일과 일치하지 않습니다: $(basename "$evidence")"

    validated_hash=$actual_hash
}

validate_asset "linux-x64" "linux" "x64"
linux_hash=$validated_hash
validate_asset "macos-aarch64" "macos" "aarch64"
macos_hash=$validated_hash

output_was_created=false
unsorted_sums="$output_dir/.SHA256SUMS.unsorted"
temporary_sums="$output_dir/.SHA256SUMS.tmp"
cleanup() {
    rm -f -- \
        "$output_dir/omluke-$version-linux-x64.tar.gz" \
        "$output_dir/omluke-$version-linux-x64.tar.gz.sha256" \
        "$output_dir/omluke-$version-linux-x64.json" \
        "$output_dir/omluke-$version-macos-aarch64.tar.gz" \
        "$output_dir/omluke-$version-macos-aarch64.tar.gz.sha256" \
        "$output_dir/omluke-$version-macos-aarch64.json" \
        "$output_dir/SHA256SUMS" "$unsorted_sums" "$temporary_sums"
    if [ "$output_was_created" = true ]; then
        rmdir "$output_dir" 2>/dev/null || :
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

if [ ! -e "$output_dir" ]; then
    mkdir -p "$output_dir"
    output_was_created=true
fi

for classifier in linux-x64 macos-aarch64; do
    archive_name="omluke-$version-$classifier.tar.gz"
    cp "$input_dir/$archive_name" "$output_dir/$archive_name"
    cp "$input_dir/$archive_name.sha256" "$output_dir/$archive_name.sha256"
    cp "$input_dir/evidence/omluke-$version-$classifier.json" \
        "$output_dir/omluke-$version-$classifier.json"
done
printf '%s  %s\n' "$linux_hash" "omluke-$version-linux-x64.tar.gz" > "$unsorted_sums"
printf '%s  %s\n' "$macos_hash" "omluke-$version-macos-aarch64.tar.gz" >> "$unsorted_sums"
LC_ALL=C sort -k2 "$unsorted_sums" > "$temporary_sums"
mv "$temporary_sums" "$output_dir/SHA256SUMS"
rm -- "$unsorted_sums"

[ "$(find "$output_dir" -maxdepth 1 -type f -name 'omluke-*.tar.gz' | wc -l | tr -d ' ')" -eq 2 ] \
    || fail "배포 파일은 정확히 두 개여야 합니다."
[ "$(find "$output_dir" -maxdepth 1 -type f -name 'omluke-*.tar.gz.sha256' | wc -l | tr -d ' ')" -eq 2 ] \
    || fail "개별 체크섬은 정확히 두 개여야 합니다."
[ "$(find "$output_dir" -maxdepth 1 -type f -name 'omluke-*.json' | wc -l | tr -d ' ')" -eq 2 ] \
    || fail "검증 근거는 정확히 두 개여야 합니다."

trap - EXIT HUP INT TERM
printf '%s\n' "RC 묶음 검증 완료: $output_dir"
