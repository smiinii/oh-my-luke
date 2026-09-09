package io.ohmyluke.profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Resolve once at launch. Identity is the canonical work folder, never the repository name or gitdir. */
public final class ProjectLocator {
    private ProjectLocator() {}

    public static Path locate(Path workingDirectory, Path home, Path explicitRoot, Path... additionalHomes) {
        Path cwd = canonical(workingDirectory);
        var homes = homes(home, additionalHomes);
        if (explicitRoot != null) {
            Path root = canonical(explicitRoot.isAbsolute() ? explicitRoot : cwd.resolve(explicitRoot));
            requireWorkFolder(root, homes);
            checkMarkers(root);
            return root;
        }
        requireWorkFolder(cwd, homes);
        for (Path candidate = cwd; candidate != null && !ambiguous(candidate, homes); candidate = candidate.getParent()) {
            Path git = candidate.resolve(".git");
            Path oml = candidate.resolve(".oml");
            checkMarkers(candidate);
            if (Files.isDirectory(git, LinkOption.NOFOLLOW_LINKS)
                    || Files.isRegularFile(git, LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(oml.resolve("profile.json"), LinkOption.NOFOLLOW_LINKS)
                    || Files.isDirectory(oml.resolve("runs"), LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
        }
        // Preserve the existing non-Git workflow: its first run creates a discoverable .oml/runs boundary.
        return cwd;
    }

    static Path canonical(Path path) {
        try {
            Path canonical = path.toAbsolutePath().normalize().toRealPath();
            if (!Files.isDirectory(canonical)) { throw new IllegalArgumentException("작업 경로는 기존 폴더여야 합니다."); }
            return canonical;
        } catch (IOException error) {
            throw new IllegalArgumentException("작업 폴더를 확인할 수 없습니다.");
        }
    }

    public static boolean needsWorkFolder(Path path, Path home, Path... additionalHomes) {
        return ambiguous(canonical(path), homes(home, additionalHomes));
    }

    private static void checkMarkers(Path root) {
        if (Files.isSymbolicLink(root.resolve(".git")) || Files.isSymbolicLink(root.resolve(".oml"))
                || Files.isSymbolicLink(root.resolve(".oml/runs"))) {
            throw new IllegalStateException("프로젝트 식별 경로는 심볼릭 링크일 수 없습니다.");
        }
    }

    private static void requireWorkFolder(Path path, java.util.Set<Path> homes) {
        if (ambiguous(path, homes)) {
            throw new IllegalArgumentException("작업 폴더를 지정해 주세요: omluke --project <작업폴더> <명령>");
        }
    }

    private static boolean ambiguous(Path path, java.util.Set<Path> homes) {
        return path.getParent() == null || homes.stream().anyMatch(home -> path.equals(home)
                || path.equals(home.resolve("Desktop")) || path.equals(home.resolve("Downloads"))
                || path.equals(home.resolve("Documents")));
    }

    private static java.util.Set<Path> homes(Path home, Path[] additionalHomes) {
        var homes = new java.util.HashSet<Path>();
        homes.add(homeBoundary(home));
        for (Path candidate : additionalHomes) { homes.add(homeBoundary(candidate)); }
        return java.util.Set.copyOf(homes);
    }

    private static Path homeBoundary(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        try { return normalized.toRealPath(); }
        catch (IOException error) {
            // Container passwd homes can be absent or deliberately hidden. Never read their settings as fallback.
            return normalized;
        }
    }
}
