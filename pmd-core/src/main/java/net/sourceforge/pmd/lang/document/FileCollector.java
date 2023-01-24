/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.document;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ProviderNotFoundException;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.annotation.Experimental;
import net.sourceforge.pmd.annotation.InternalApi;
import net.sourceforge.pmd.internal.util.AssertionUtil;
import net.sourceforge.pmd.lang.Language;
import net.sourceforge.pmd.lang.LanguageVersion;
import net.sourceforge.pmd.lang.LanguageVersionDiscoverer;
import net.sourceforge.pmd.util.IOUtil;
import net.sourceforge.pmd.util.log.MessageReporter;

/**
 * Collects files to analyse before a PMD run. This API allows opening
 * zip files and makes sure they will be closed at the end of a run.
 *
 * @author Clément Fournier
 */
@SuppressWarnings("PMD.CloseResource")
public final class FileCollector implements AutoCloseable {

    private final Set<TextFile> allFilesToProcess = new LinkedHashSet<>();
    private final List<Closeable> resourcesToClose = new ArrayList<>();
    private Charset charset = StandardCharsets.UTF_8;
    private final LanguageVersionDiscoverer discoverer;
    private final MessageReporter reporter;
    private final String outerFsDisplayName;
    @Deprecated
    private final List<String> legacyRelativizeRoots = new ArrayList<>();
    private final List<Path> relativizeRootPaths = new ArrayList<>();
    private boolean closed;

    // construction

    private FileCollector(LanguageVersionDiscoverer discoverer, MessageReporter reporter, String outerFsDisplayName) {
        this.discoverer = discoverer;
        this.reporter = reporter;
        this.outerFsDisplayName = outerFsDisplayName;
    }

    /**
     * Internal API: please use {@link PmdAnalysis#files()} instead of
     * creating a collector yourself.
     */
    @InternalApi
    public static FileCollector newCollector(LanguageVersionDiscoverer discoverer, MessageReporter reporter) {
        return new FileCollector(discoverer, reporter, null);
    }

    // public behaviour

    /**
     * Returns an unmodifiable list of all files that have been collected.
     *
     * <p>Internal: This might be unstable until PMD 7, but it's internal.
     */
    @InternalApi
    public List<TextFile> getCollectedFiles() {
        if (closed) {
            throw new IllegalStateException("Collector was closed!");
        }
        List<TextFile> allFilesToProcess = new ArrayList<>(this.allFilesToProcess);
        Collections.sort(allFilesToProcess, new Comparator<TextFile>() {
            @Override
            public int compare(TextFile o1, TextFile o2) {
                return o1.getPathId().compareTo(o2.getPathId());
            }
        });
        return Collections.unmodifiableList(allFilesToProcess);
    }


    /**
     * Returns the reporter for the file collection phase.
     */
    @InternalApi
    public MessageReporter getReporter() {
        return reporter;
    }

    /**
     * Close registered resources like zip files.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        IOException exception = IOUtil.closeAll(resourcesToClose);
        if (exception != null) {
            reporter.errorEx("Error while closing resources", exception);
        }
    }

    // collection

    /**
     * Add a file, language is determined automatically from
     * the extension/file patterns. The encoding is the current
     * encoding ({@link #setCharset(Charset)}).
     *
     * @param file File to add
     *
     * @return True if the file has been added
     */
    public boolean addFile(Path file) {
        if (!Files.isRegularFile(file)) {
            reporter.error("Not a regular file {0}", file);
            return false;
        }
        LanguageVersion languageVersion = discoverLanguage(file.toString());
        if (languageVersion != null) {
            return addFileImpl(new NioTextFile(file, charset, languageVersion, getDisplayName(file)));
        }
        return false;
    }

    /**
     * Add a file with the given language (which overrides the file patterns).
     * The encoding is the current encoding ({@link #setCharset(Charset)}).
     *
     * @param file     Path to a file
     * @param language A language. The language version will be taken to be the
     *                 contextual default version.
     *
     * @return True if the file has been added
     */
    public boolean addFile(Path file, Language language) {
        AssertionUtil.requireParamNotNull("language", language);
        if (!Files.isRegularFile(file)) {
            reporter.error("Not a regular file {0}", file);
            return false;
        }
        NioTextFile nioTextFile = new NioTextFile(file, charset, discoverer.getDefaultLanguageVersion(language), getDisplayName(file));
        return addFileImpl(nioTextFile);
    }

    /**
     * Add a pre-configured text file. The language version will be checked
     * to match the contextual default for the language (the file cannot be added
     * if it has a different version).
     *
     * @return True if the file has been added
     */
    public boolean addFile(TextFile textFile) {
        AssertionUtil.requireParamNotNull("textFile", textFile);
        if (checkContextualVersion(textFile)) {
            return addFileImpl(textFile);
        }
        return false;
    }

    /**
     * Add a text file given its contents and a name. The language version
     * will be determined from the name as usual.
     *
     * @return True if the file has been added
     */
    public boolean addSourceFile(String sourceContents, String pathId) {
        AssertionUtil.requireParamNotNull("sourceContents", sourceContents);
        AssertionUtil.requireParamNotNull("pathId", pathId);

        LanguageVersion version = discoverLanguage(pathId);
        if (version != null) {
            return addFileImpl(new StringTextFile(sourceContents, pathId, pathId, version));
        }

        return false;
    }

    private boolean addFileImpl(TextFile textFile) {
        reporter.trace("Adding file {0} (lang: {1}) ", textFile.getPathId(), textFile.getLanguageVersion().getTerseName());
        if (allFilesToProcess.add(textFile)) {
            return true;
        }
        reporter.trace("File was already collected, skipping");
        return false;
    }

    private LanguageVersion discoverLanguage(String file) {
        if (discoverer.getForcedVersion() != null) {
            return discoverer.getForcedVersion();
        }
        List<Language> languages = discoverer.getLanguagesForFile(file);

        if (languages.isEmpty()) {
            reporter.trace("File {0} matches no known language, ignoring", file);
            return null;
        }
        Language lang = languages.get(0);
        if (languages.size() > 1) {
            reporter.trace("File {0} matches multiple languages ({1}), selecting {2}", file, languages, lang);
        }
        return discoverer.getDefaultLanguageVersion(lang);
    }

    /**
     * Whether the LanguageVersion of the file matches the one set in
     * the {@link LanguageVersionDiscoverer}. This is required to ensure
     * that all files for a given language have the same language version.
     */
    private boolean checkContextualVersion(TextFile textFile) {
        LanguageVersion fileVersion = textFile.getLanguageVersion();
        Language language = fileVersion.getLanguage();
        LanguageVersion contextVersion = discoverer.getDefaultLanguageVersion(language);
        if (!fileVersion.equals(contextVersion)) {
            reporter.error(
                "Cannot add file {0}: version ''{2}'' does not match ''{1}''",
                textFile.getPathId(),
                contextVersion,
                fileVersion
            );
            return false;
        }
        return true;
    }

    private String getDisplayName(Path file) {
        String localDisplayName = getLocalDisplayName(file);
        if (outerFsDisplayName != null) {
            return outerFsDisplayName + "!" + localDisplayName;
        }
        return localDisplayName;
    }

    private String getLocalDisplayName(Path file) {
        if (!relativizeRootPaths.isEmpty()) {
            // takes precedence over legacy behavior
            return getDisplayName(file, relativizeRootPaths);
        }
        return getDisplayNameLegacy(file, legacyRelativizeRoots);
    }

    /**
     * Return the textfile's display name.
     */
    static String getDisplayNameLegacy(Path file, List<String> relativizeRoots) {
        String fileName = file.toString();
        for (String root : relativizeRoots) {
            if (file.startsWith(root)) {
                if (fileName.startsWith(File.separator, root.length())) {
                    // remove following '/'
                    return fileName.substring(root.length() + 1);
                }
                return fileName.substring(root.length());
            }
        }
        return fileName;
    }

    /**
     * Return the textfile's display name. Takes the shortest path we
     * can construct from the relativize roots.
     * test only
     */
    static String getDisplayName(Path file, List<Path> relativizeRoots) {
        Path best = file;
        for (Path root : relativizeRoots) {
            Path candidate;
            if (isFileSystemRoot(root)) {
                // Absolutize the path. Since the relativize roots are
                // sorted by ascending length, this should be the first in the list
                // (so another root can override it).
                best = file.toAbsolutePath();
                continue;
            } else {
                if (!root.getFileSystem().equals(file.getFileSystem())) {
                    // maybe the file is in a zip
                    root = file.getFileSystem().getPath(root.toString()); // SUPPRESS CHECKSTYLE ModifiedControlVariable
                }
                if (root.isAbsolute() != file.isAbsolute()) { // this causes IllegalArgumentException
                    root = root.toAbsolutePath(); // SUPPRESS CHECKSTYLE ModifiedControlVariable
                    file = file.toAbsolutePath();
                }
                candidate = root.relativize(file);
            }
            // take the shortest path.
            if (candidate.getNameCount() < best.getNameCount()) {
                best = candidate;
            }
        }
        return best.toString();
    }

    /** Return whether the path is the root path (/). */
    private static boolean isFileSystemRoot(Path root) {
        return root.isAbsolute() && root.getNameCount() == 0;
    }


    /**
     * Add a directory recursively using {@link #addFile(Path)} on
     * all regular files.
     *
     * @param dir Directory path
     *
     * @return True if the directory has been added
     */
    public boolean addDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            reporter.error("Not a directory {0}", dir);
            return false;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) {
                    FileCollector.this.addFile(file);
                }
                return super.visitFile(file, attrs);
            }
        });
        return true;
    }


    /**
     * Add a file or directory recursively. Language is determined automatically
     * from the extension/file patterns.
     *
     * @return True if the file or directory has been added
     */
    public boolean addFileOrDirectory(Path file) throws IOException {
        if (Files.isDirectory(file)) {
            return addDirectory(file);
        } else if (Files.isRegularFile(file)) {
            return addFile(file);
        } else {
            reporter.error("Not a file or directory {0}", file);
            return false;
        }
    }

    /**
     * Opens a zip file and returns a FileSystem for its contents, so
     * it can be explored with the {@link Path} API. You can then call
     * {@link #addFile(Path)} and such. The zip file is registered as
     * a resource to close at the end of analysis.
     */
    @Experimental
    public void addZipFile(Path zipFile) throws IOException {
        if (!Files.isRegularFile(zipFile)) {
            throw new IllegalArgumentException("Not a regular file: " + zipFile);
        }
        URI zipUri = URI.create("jar:" + zipFile.toUri());
        FileSystem fs;
        boolean isNewFileSystem = false;
        try {
            // find an existing file system, may fail
            fs = FileSystems.getFileSystem(zipUri);
        } catch (FileSystemNotFoundException ignored) {
            // if it fails, try to create it.
            try {
                fs = FileSystems.newFileSystem(zipUri, Collections.<String, Object>emptyMap());
                isNewFileSystem = true;
            } catch (ProviderNotFoundException | IOException e) {
                reporter.errorEx("Cannot open zip file " + zipFile, e);
                return;
            }
        }
        try (FileCollector zipCollector = newZipCollector(zipFile)) {
            for (Path zipRoot : fs.getRootDirectories()) {
                zipCollector.addFileOrDirectory(zipRoot);
            }
            this.absorb(zipCollector);
            if (isNewFileSystem) {
                resourcesToClose.add(fs);
            }

        } catch (IOException ioe) {
            reporter.errorEx("Error reading zip file " + zipFile + ", will be skipped", ioe);
            fs.close();
        }
    }


    /** A collector that prefixes the display name of the files it will contain with the path of the zip. */
    @Experimental
    private FileCollector newZipCollector(Path zipFilePath) {
        String zipDisplayName = getDisplayName(zipFilePath);
        return new FileCollector(discoverer, reporter, zipDisplayName);
    }

    // configuration

    /**
     * Sets the charset to use for subsequent calls to {@link #addFile(Path)}
     * and other overloads using a {@link Path}.
     *
     * @param charset A charset
     */
    public void setCharset(Charset charset) {
        this.charset = Objects.requireNonNull(charset);
    }

    /**
     * Add a prefix that is used to relativize file paths as their display name.
     * For instance, when adding a file {@code /tmp/src/main/java/org/foo.java},
     * and relativizing with {@code /tmp/src/}, the registered {@link  TextFile}
     * will have a path id of {@code /tmp/src/main/java/org/foo.java}, and a
     * display name of {@code main/java/org/foo.java}.
     *
     * <p>This only matters for files added from a {@link Path} object.
     *
     * @param prefix Prefix to relativize (if a directory, include a trailing slash)
     *
     * @deprecated Use {@link #relativizeWith(Path)}
     */
    @Deprecated
    public void relativizeWith(String prefix) {
        this.legacyRelativizeRoots.add(Objects.requireNonNull(prefix));
    }

    /**
     * Add a prefix that is used to relativize file paths as their display name.
     * For instance, when adding a file {@code /tmp/src/main/java/org/foo.java},
     * and relativizing with {@code /tmp/src/}, the registered {@link TextFile}
     * will have a path id of {@code /tmp/src/main/java/org/foo.java}, and a
     * display name of {@code main/java/org/foo.java}.
     *
     * <p>This only matters for files added from a {@link Path} object.
     *
     * @param path Path with which to relativize
     */
    public void relativizeWith(Path path) {
        this.relativizeRootPaths.add(Objects.requireNonNull(path));
        Collections.sort(relativizeRootPaths, new Comparator<Path>() {
            @Override
            public int compare(Path o1, Path o2) {
                int lengthCmp = Integer.compare(o1.getNameCount(), o2.getNameCount());
                return lengthCmp == 0 ? o1.compareTo(o2) : lengthCmp;
            }
        });
    }

    // filtering

    /**
     * Remove all files collected by the given collector from this one.
     */
    public void exclude(FileCollector excludeCollector) {
        HashSet<TextFile> toExclude = new HashSet<>(excludeCollector.allFilesToProcess);
        for (Iterator<TextFile> iterator = allFilesToProcess.iterator(); iterator.hasNext();) {
            TextFile file = iterator.next();
            if (toExclude.contains(file)) {
                reporter.trace("Excluding file {0}", file.getPathId());
                iterator.remove();
            }
        }
    }

    /**
     * Add all files collected in the other collector into this one.
     * Transfers resources to close as well. The parameter is left empty.
     */
    public void absorb(FileCollector otherCollector) {
        this.allFilesToProcess.addAll(otherCollector.allFilesToProcess);
        this.resourcesToClose.addAll(otherCollector.resourcesToClose);
        otherCollector.allFilesToProcess.clear();
        otherCollector.resourcesToClose.clear();
    }

    /**
     * Exclude all collected files whose language is not part of the given
     * collection.
     */
    public void filterLanguages(Set<Language> languages) {
        for (Iterator<TextFile> iterator = allFilesToProcess.iterator(); iterator.hasNext();) {
            TextFile file = iterator.next();
            Language lang = file.getLanguageVersion().getLanguage();
            if (!languages.contains(lang)) {
                reporter.trace("Filtering out {0}, no rules for language {1}", file.getPathId(), lang);
                iterator.remove();
            }
        }
    }


    @Override
    public String toString() {
        return "FileCollector{filesToProcess=" + allFilesToProcess + '}';
    }
}
