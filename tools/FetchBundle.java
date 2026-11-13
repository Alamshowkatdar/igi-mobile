import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Downloads the playigi.com web build and the game data packs, then packages them as
 * app/src/main/assets/bundle/{shell.zip,data.zip,version.txt}.
 *
 * Usage:
 *   java tools/FetchBundle.java app/src/main/assets/bundle
 *   java tools/FetchBundle.java app/src/main/assets/bundle --hd --flac
 *
 * Only run this if you have permission to redistribute the game content. The resulting
 * APK contains copyrighted assets.
 */
public final class FetchBundle {

    private static final String SITE = "https://playigi.com/game/";
    private static final String DATA = "https://data.playigi.com/";
    private static final String SHELL_LIST = SITE + "shell-files.json";
    private static final String INDEX_URL = DATA + "offline-index.json";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
    // data.playigi.com answers 403 without this; the web build always sends it.
    private static final String REFERER = "https://playigi.com/game/";
    private static final int THREADS = 8;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(30))
        .build();

    public static void main(String[] args) throws Exception {
        Path bundleDir = Paths.get(args.length > 0 ? args[0] : "app/src/main/assets/bundle");
        Set<String> flags = new HashSet<>();
        for (int i = 1; i < args.length; i++) flags.add(args[i].toLowerCase(Locale.ROOT));

        boolean includeHd = flags.contains("--hd");
        boolean includeFlac = flags.contains("--flac");

        Path cache = Paths.get("tools", ".bundle-cache");
        Path shellCache = cache.resolve("shell");
        Path dataCache = cache.resolve("data");
        Files.createDirectories(shellCache);
        Files.createDirectories(dataCache);

        System.out.println("Fetching shell list and offline index...");
        String shellListText = fetchText(SHELL_LIST);
        String indexText = fetchText(INDEX_URL);

        Object parsedShell = Json.parse(shellListText);
        List<String> shellPaths = new ArrayList<>();
        for (Object entry : (List<?>) parsedShell) shellPaths.add(String.valueOf(entry));

        Map<String, Object> index = asObject(Json.parse(indexText));
        Map<String, Object> files = asObject(index.get("files"));

        List<String> categories = new ArrayList<>(List.of("common", "sd", "touch"));
        if (includeHd) categories.add("hd");
        if (includeFlac) categories.add("flac");

        // path -> expected size (0 when unknown)
        Map<String, Long> dataFiles = new LinkedHashMap<>();
        long dataBytes = 0L;
        for (String category : categories) {
            Object raw = files.get(category);
            if (!(raw instanceof List)) throw new IllegalStateException("Missing category: " + category);
            for (Object item : (List<?>) raw) {
                List<?> pair = (List<?>) item;
                String path = String.valueOf(pair.get(0));
                long size = ((Number) pair.get(1)).longValue();
                Long previous = dataFiles.put(path, size);
                if (previous == null) dataBytes += size;
            }
        }

        System.out.printf("Shell files: %d | Data files: %d (%.1f MB) | hd=%s flac=%s%n",
            shellPaths.size(), dataFiles.size(), dataBytes / 1048576.0, includeHd, includeFlac);

        downloadAll(shellPaths, SITE, shellCache, Collections.emptyMap(), true);
        downloadAll(new ArrayList<>(dataFiles.keySet()), DATA, dataCache, dataFiles, false);

        Path assetsDir = bundleDir.toAbsolutePath().getParent();
        if (assetsDir != null) {
            Files.createDirectories(assetsDir);
            Files.writeString(assetsDir.resolve("offline-index.json"), indexText);
        }

        Files.createDirectories(bundleDir);
        System.out.println("Writing shell.zip...");
        zip(shellCache, bundleDir.resolve("shell.zip"), 6);
        System.out.println("Writing data.zip...");
        zip(dataCache, bundleDir.resolve("data.zip"), 1);

        String version = sha256(indexText + "\n" + shellListText + "\n" + categories + "\n" + dataBytes);
        int shellEntries = countFiles(shellCache);
        int dataEntries = countFiles(dataCache);
        String metadata = "version=" + version + "\n"
            + "dataBytes=" + dataBytes + "\n"
            + "shellEntries=" + shellEntries + "\n"
            + "dataEntries=" + dataEntries + "\n"
            + "categories=" + String.join(",", categories) + "\n";
        Files.writeString(bundleDir.resolve("version.txt"), metadata);
        System.out.println("Bundle version: " + version);
        System.out.println("Done: " + bundleDir.toAbsolutePath());
    }

    private static void downloadAll(
        List<String> paths,
        String base,
        Path root,
        Map<String, Long> expectedSizes,
        boolean forceIndex
    ) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        AtomicInteger done = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();

        for (String path : paths) {
            futures.add(pool.submit(() -> {
                Path target = root.resolve(path.replace('/', java.io.File.separatorChar));
                long expected = expectedSizes.getOrDefault(path, 0L);
                boolean overwrite = forceIndex && path.equals("index.html");
                try {
                    boolean fetched = download(base + path, target, expected, overwrite);
                    if (fetched) bytes.addAndGet(Files.size(target));
                } catch (Exception error) {
                    failures.add(path + " -> " + error.getMessage());
                }
                int count = done.incrementAndGet();
                if (count % 100 == 0 || count == paths.size()) {
                    System.out.printf("  %d/%d files, %.1f MB fetched%n",
                        count, paths.size(), bytes.get() / 1048576.0);
                }
            }));
        }

        pool.shutdown();
        if (!pool.awaitTermination(2, TimeUnit.HOURS)) pool.shutdownNow();

        for (Future<?> future : futures) future.get();
        if (!failures.isEmpty()) {
            System.out.println("Failed files:");
            for (String failure : failures) System.out.println("  " + failure);
            throw new IllegalStateException(failures.size() + " downloads failed");
        }
    }

    private static boolean download(String url, Path target, long expected, boolean overwrite)
        throws IOException, InterruptedException {
        if (Files.isRegularFile(target) && !overwrite) {
            long length = Files.size(target);
            if (expected > 0 ? length == expected : length > 0) return false;
        }
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        IOException last = null;

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", REFERER)
                    .header("Accept", "*/*")
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();
                HttpResponse<Path> response = CLIENT.send(
                    request,
                    HttpResponse.BodyHandlers.ofFile(
                        part,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING
                    )
                );
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode());
                }
                long length = Files.size(part);
                if (expected > 0 && length != expected) {
                    throw new IOException("size " + length + " != expected " + expected);
                }
                if (length <= 0) throw new IOException("empty body");
                try {
                    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailure) {
                    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return true;
            } catch (IOException error) {
                last = error;
                Files.deleteIfExists(part);
                Thread.sleep(500L * attempt);
            }
        }
        throw last != null ? last : new IOException("download failed");
    }

    private static String fetchText(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .header("Accept", "*/*")
            .timeout(Duration.ofMinutes(2))
            .GET()
            .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " for " + url);
        return response.body();
    }

    private static void zip(Path root, Path zipFile, int level) throws IOException {
        List<Path> files = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        files.sort((a, b) -> relative(root, a).compareTo(relative(root, b)));

        Files.createDirectories(zipFile.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(
            new BufferedOutputStream(Files.newOutputStream(zipFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING), 1 << 20))) {
            zip.setLevel(level);
            byte[] buffer = new byte[256 * 1024];
            for (Path file : files) {
                ZipEntry entry = new ZipEntry(relative(root, file));
                entry.setTime(0L);
                zip.putNextEntry(entry);
                try (InputStream input = new BufferedInputStream(Files.newInputStream(file), 1 << 20)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) zip.write(buffer, 0, read);
                }
                zip.closeEntry();
            }
        }
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static int countFiles(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            return (int) walk.filter(Files::isRegularFile).count();
        }
    }

    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8"));
        StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append(String.format("%02x", b));
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value) {
        return (Map<String, Object>) value;
    }

    /** Tiny JSON parser, enough for the port's manifests. */
    static final class Json {
        private final String text;
        private int index;

        private Json(String text) {
            this.text = text;
        }

        static Object parse(String text) {
            Json parser = new Json(text);
            Object value = parser.value();
            parser.skipSpace();
            if (parser.index != text.length()) throw new IllegalArgumentException("Trailing JSON at " + parser.index);
            return value;
        }

        private Object value() {
            skipSpace();
            char c = text.charAt(index);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return number();
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> out = new LinkedHashMap<>();
            index++;
            skipSpace();
            if (text.charAt(index) == '}') { index++; return out; }
            while (true) {
                skipSpace();
                String key = string();
                skipSpace();
                if (text.charAt(index++) != ':') throw new IllegalArgumentException("Expected ':' at " + index);
                out.put(key, value());
                skipSpace();
                char c = text.charAt(index++);
                if (c == '}') return out;
                if (c != ',') throw new IllegalArgumentException("Expected ',' at " + index);
            }
        }

        private List<Object> array() {
            List<Object> out = new ArrayList<>();
            index++;
            skipSpace();
            if (text.charAt(index) == ']') { index++; return out; }
            while (true) {
                out.add(value());
                skipSpace();
                char c = text.charAt(index++);
                if (c == ']') return out;
                if (c != ',') throw new IllegalArgumentException("Expected ',' at " + index);
            }
        }

        private String string() {
            if (text.charAt(index) != '"') throw new IllegalArgumentException("Expected string at " + index);
            index++;
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = text.charAt(index++);
                if (c == '"') return out.toString();
                if (c == '\\') {
                    char escape = text.charAt(index++);
                    switch (escape) {
                        case '"': out.append('"'); break;
                        case '\\': out.append('\\'); break;
                        case '/': out.append('/'); break;
                        case 'b': out.append('\b'); break;
                        case 'f': out.append('\f'); break;
                        case 'n': out.append('\n'); break;
                        case 'r': out.append('\r'); break;
                        case 't': out.append('\t'); break;
                        case 'u':
                            out.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                            index += 4;
                            break;
                        default: throw new IllegalArgumentException("Bad escape at " + index);
                    }
                } else {
                    out.append(c);
                }
            }
        }

        private Number number() {
            int start = index;
            while (index < text.length() && "+-0123456789.eE".indexOf(text.charAt(index)) >= 0) index++;
            String raw = text.substring(start, index);
            if (raw.contains(".") || raw.contains("e") || raw.contains("E")) return Double.parseDouble(raw);
            return Long.parseLong(raw);
        }

        private void expect(String word) {
            if (!text.startsWith(word, index)) throw new IllegalArgumentException("Expected " + word + " at " + index);
            index += word.length();
        }

        private void skipSpace() {
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') index++;
                else break;
            }
        }
    }
}
