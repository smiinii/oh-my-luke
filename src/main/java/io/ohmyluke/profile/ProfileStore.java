package io.ohmyluke.profile;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Small, strict, atomically replaced operator settings; reads never create a file. */
final class ProfileStore {
    private static final int MAX_BYTES = 16_384;
    private static final Object[] LOCKS = java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    static {
        for (var shape : new com.fasterxml.jackson.databind.cfg.CoercionInputShape[] {
                com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
                com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,
                com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean}) {
            JSON.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual)
                    .setCoercion(shape, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
        }
    }
    private final Path folder;
    private final Path file;
    private final String projectRoot;

    ProfileStore(Path anchor, boolean project) {
        Path root = ProjectLocator.canonical(anchor);
        folder = root.resolve(".oml");
        file = folder.resolve(project ? "profile.json" : "settings.json");
        projectRoot = project ? root.toString() : null;
    }

    Optional<ExecutionProfile> load() {
        try {
            checkFolder();
            if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) { return Optional.empty(); }
            regularFile(file);
            byte[] bytes;
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.allocate(MAX_BYTES + 1);
                while (buffer.hasRemaining() && channel.read(buffer) != -1) { /* bounded even if the file grows */ }
                if (buffer.position() > MAX_BYTES) { throw invalid(); }
                bytes = java.util.Arrays.copyOf(buffer.array(), buffer.position());
            }
            Settings settings = JSON.readValue(bytes, Settings.class);
            if (settings == null || settings.schemaVersion() != 1 || settings.profile() == null
                    || !Objects.equals(projectRoot, settings.projectRoot())) { throw invalid(); }
            return Optional.of(settings.profile());
        } catch (IOException | IllegalArgumentException error) {
            throw invalid(); // Parser text can include user input. Never expose it or quietly use defaults.
        }
    }

    boolean initialize() {
        return locked(() -> {
            if (load().isPresent()) { return false; }
            write(ExecutionProfile.defaults());
            return true;
        });
    }

    void changeIfUnchanged(ExecutionProfile expected, ExecutionProfile replacement) {
        locked(() -> {
            if (!Objects.equals(load().orElse(null), expected)) {
                throw new IllegalStateException("다른 곳에서 설정이 변경됐습니다. 선택 화면을 다시 열어 확인하세요.");
            }
            if (replacement == null) {
                try { Files.deleteIfExists(file); }
                catch (IOException error) { throw new IllegalStateException("프로젝트 설정을 해제하지 못했습니다."); }
            } else { write(replacement); }
            return null;
        });
    }

    void save(ExecutionProfile profile) {
        Objects.requireNonNull(profile);
        locked(() -> { load(); write(profile); return null; });
    }

    boolean reset() {
        if (load().isEmpty()) { return false; }
        return locked(() -> {
            if (load().isEmpty()) { return false; }
            try { return Files.deleteIfExists(file); }
            catch (IOException error) { throw new IllegalStateException("프로젝트 설정을 해제하지 못했습니다."); }
        });
    }

    private <T> T locked(Supplier<T> action) {
        synchronized (LOCKS[Math.floorMod(file.hashCode(), LOCKS.length)]) {
            try {
                checkFolder();
                if (Files.notExists(folder, LinkOption.NOFOLLOW_LINKS)) {
                    try { Files.createDirectory(folder, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))); }
                    catch (UnsupportedOperationException error) { Files.createDirectory(folder); }
                    catch (java.nio.file.FileAlreadyExistsException ignored) { /* another OML process initialized it */ }
                }
                checkFolder();
                Path lock = folder.resolve(file.getFileName() + ".lock");
                if (Files.exists(lock, LinkOption.NOFOLLOW_LINKS)) { regularFile(lock); }
                try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS); var acquired = channel.tryLock()) {
                    if (acquired == null) { throw new IllegalStateException("다른 OML 프로세스가 설정을 변경 중입니다. 다시 시도해 주세요."); }
                    return action.get();
                }
            } catch (IOException error) {
                throw new IllegalStateException("설정을 잠그거나 저장할 수 없습니다. 기존 설정은 자동 초기화하지 않습니다.");
            }
        }
    }

    private void write(ExecutionProfile profile) {
        Path temporary = null;
        try {
            checkFolder();
            byte[] bytes = JSON.writeValueAsBytes(new Settings(1, projectRoot, profile));
            if (bytes.length > MAX_BYTES) { throw invalid(); }
            try { temporary = Files.createTempFile(folder, "profile-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
            catch (UnsupportedOperationException error) { temporary = Files.createTempFile(folder, "profile-", ".tmp"); }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) { channel.write(buffer); }
                channel.force(true);
            }
            checkFolder();
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) { regularFile(file); }
            // No non-atomic fallback: unsupported filesystems fail without replacing valid settings.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            throw new IllegalStateException("설정을 원자적으로 저장하지 못했습니다.");
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* never read as settings */ }
            }
        }
    }

    private void checkFolder() throws IOException {
        if (Files.exists(folder, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(folder))) { throw invalid(); }
    }

    private static void regularFile(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) { throw invalid(); }
        if (path.getFileSystem().supportedFileAttributeViews().contains("unix")
                && ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1) { throw invalid(); }
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("설정 파일이 손상됐거나 지원하지 않는 형식·경로·프로젝트입니다. 자동으로 덮어쓰지 않습니다.");
    }

    private record Settings(int schemaVersion, String projectRoot, ExecutionProfile profile) {}
}
