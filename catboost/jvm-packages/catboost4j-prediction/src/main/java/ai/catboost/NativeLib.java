package ai.catboost;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.constraints.NotNull;
import java.io.*;
import java.lang.reflect.Field;

/**
 * Shared library loader, this class is intended to be an extension point for users of this modele. If you want to
 * define a custom loader for shared library you may override this class via `-classpath`, only requirement on your
 * your class will be to have {@link #handle()} method that returns CatBoostJNI class.
 */
class NativeLib {
    private static final Logger logger = LoggerFactory.getLogger(NativeLib.class);

    private static final String nativeLibDirectory = "../lib";

    static {
        try {
            smartLoad("catboost4j-prediction");
        } catch (Exception ex) {
            logger.error("Failed to load native library", ex);
            throw new RuntimeException(ex);
        }
    }

    /**
     * @return JNI handle for CatBoost model application.
     */
    @NotNull
    public static CatBoostJNI handle() {
        return SingletonHolder.catBoostJNI;
    }

    private static final class SingletonHolder {
        public static final CatBoostJNI catBoostJNI = new CatBoostJNI();
    }

    /**
     * Load libName, first will try try to load libName from default location then will try to load library from JAR.
     *
     * @param libName
     * @throws IOException
     */
    private static void smartLoad(final @NotNull String libName) throws IOException {
        addDirectoryToNativeLibSearchList(nativeLibDirectory);
        try {
            System.loadLibrary(libName);
        } catch (UnsatisfiedLinkError e) {
            logger.debug(e.getMessage());
            try {
                loadNativeLibraryFromJar(libName);
            } catch (IOException ioe) {
                logger.error("failed to load native library from both default location and JAR");
                throw ioe;
            }
        }
    }

    @NotNull
    private static String getCurrentMachineResourcesDir() {
        final String osArch = System.getProperty("os.arch");
        String osName = System.getProperty("os.name").toLowerCase();

        // Java doesn't seem to have analog for python's `sys.platform` or `platform.platform`, so we have to do it by
        // hand.
        if (osName.contains("mac")) {
            osName = "darwin";
        } else if (osName.contains("win")) {
            osName = "win32";
        }

        // Will result in something like "linux-x86_64"
        return osName + "-" + osArch;
    }

    private static void loadNativeLibraryFromJar(final @NotNull String libName) throws IOException {
        final String pathWithinJar = "/" + getCurrentMachineResourcesDir() + "/lib/" + System.mapLibraryName(libName);
        final String tempLibPath = createTemporaryFileFromJar(pathWithinJar);
        System.load(tempLibPath);
    }

    private static void copyFileFromJar(final @NotNull String pathWithinJar, final @NotNull String pathOnDisk) throws IOException {
        byte[] copyBuffer = new byte[4 * 1024];
        int bytesRead;

        try(OutputStream out = new BufferedOutputStream(new FileOutputStream(pathOnDisk));
            InputStream in = NativeLib.class.getResourceAsStream(pathWithinJar)) {

            if (in == null) {
                throw new FileNotFoundException("File " + pathWithinJar + " was not found inside JAR.");
            }

            while ((bytesRead = in.read(copyBuffer)) != -1) {
                out.write(copyBuffer, 0, bytesRead);
            }
        }
    }

    @NotNull
    private static String createTemporaryFileFromJar(final @NotNull String pathWithinJar) throws IOException, IllegalArgumentException {
        if (!pathWithinJar.startsWith("/")) {
            throw new IllegalArgumentException("Path must be absolute (start with '/')");
        }

        if (pathWithinJar.endsWith("/")) {
            throw new IllegalArgumentException("Must be a path to file not directory (ends with '/')");
        }

        String[] parts = pathWithinJar.split("/");
        final String filename = parts[parts.length - 1];

        parts = filename.split("\\.", 2);
        final String prefix = parts[0];
        final String suffix = parts.length > 1 ? "." + parts[parts.length - 1] : null;

        final File libOnDisk = File.createTempFile(prefix, suffix);
        libOnDisk.deleteOnExit();

        copyFileFromJar(pathWithinJar, libOnDisk.getPath());

        return libOnDisk.getAbsolutePath();
    }

    /**
     * Add dirToAdd to java.library.path so that native libraries will be loaded automatically from dirToAdd
     *
     * @param dirToAdd directory with native libraries
     * @throws IOException exception
     */
    private static void addDirectoryToNativeLibSearchList(final @NotNull String dirToAdd) throws IOException {
        try {
            // TODO(yazevnul): Java 10 is not happy about this monkey patching and shows warnings, maybe there is a
            // different way to solve this problem?
            Field userPathsField = ClassLoader.class.getDeclaredField("usr_paths");
            userPathsField.setAccessible(true);

            String[] paths = (String[])userPathsField.get(null);

            for (String path : paths) {
                if (path.equals(dirToAdd)) {
                    return;
                }
            }

            String[] newPaths = new String[paths.length + 1];
            System.arraycopy(paths, 0, newPaths, 0, paths.length);
            newPaths[newPaths.length - 1] = dirToAdd;
            userPathsField.set(null, newPaths);
        } catch (NoSuchFieldException e) {
            logger.error(e.getMessage());
            throw new IOException("Failed to get field handle for `usr_path`");
        } catch (IllegalAccessException e) {
            logger.error(e.getMessage());
            throw new IOException("failed to set field handle for `usr_path`");
        }
    }
}
