package io.github.navuk35.surrealdb.spring.surge;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MigrationScanner {

    private static final Pattern VERSIONED = Pattern.compile("V(\\d+(?:_\\d+)*)__(.+)\\.surql");
    private static final Pattern REPEATABLE = Pattern.compile("R__(.+)\\.surql");

    private final PathMatchingResourcePatternResolver resolver =
            new PathMatchingResourcePatternResolver();

    public record ScanResult(List<MigrationScript> versioned, List<MigrationScript> repeatable) {
    }

    public ScanResult scan(List<String> locations) {
        Map<MigrationVersion, MigrationScript> versionedByVersion = new TreeMap<>();
        List<MigrationScript> repeatable = new ArrayList<>();

        for (String location : locations) {
            for (Resource resource : resourcesAt(location)) {
                MigrationScript script = parse(resource);
                if (script.type() == MigrationType.VERSIONED) {
                    MigrationScript duplicate = versionedByVersion.putIfAbsent(script.version(), script);
                    if (duplicate != null) {
                        throw new SurgeException("Found duplicate migration version "
                                + script.version() + ": '" + duplicate.script()
                                + "' and '" + script.script() + "'");
                    }
                }
                else {
                    repeatable.add(script);
                }
            }
        }
        repeatable.sort(Comparator.comparing(MigrationScript::script));
        return new ScanResult(List.copyOf(versionedByVersion.values()), List.copyOf(repeatable));
    }

    private Resource[] resourcesAt(String location) {
        String pattern = location.endsWith("/") ? location + "*.surql" : location + "/*.surql";
        try {
            return resolver.getResources(pattern);
        }
        catch (FileNotFoundException ex) {
            return new Resource[0];
        }
        catch (IOException ex) {
            throw new SurgeException("Failed to scan migration location '" + location + "'", ex);
        }
    }

    private MigrationScript parse(Resource resource) {
        String filename = resource.getFilename();
        if (filename == null) {
            throw new SurgeException("Migration resource has no file name: " + resource);
        }
        String content = read(resource);
        String checksum = sha256(content);

        Matcher versioned = VERSIONED.matcher(filename);
        if (versioned.matches()) {
            return new MigrationScript(MigrationType.VERSIONED,
                    MigrationVersion.parse(versioned.group(1)),
                    versioned.group(2).replace('_', ' '),
                    filename, content, checksum);
        }
        Matcher repeatable = REPEATABLE.matcher(filename);
        if (repeatable.matches()) {
            return new MigrationScript(MigrationType.REPEATABLE, null,
                    repeatable.group(1).replace('_', ' '),
                    filename, content, checksum);
        }
        throw new SurgeException("Invalid migration file name '" + filename
                + "' — expected V<version>__<description>.surql or R__<description>.surql");
    }

    private String read(Resource resource) {
        try {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            // normalize line endings so checksums match across operating systems
            return content.replace("\r\n", "\n");
        }
        catch (IOException ex) {
            throw new SurgeException("Failed to read migration '" + resource.getFilename() + "'", ex);
        }
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new SurgeException("SHA-256 not available", ex);
        }
    }
}
