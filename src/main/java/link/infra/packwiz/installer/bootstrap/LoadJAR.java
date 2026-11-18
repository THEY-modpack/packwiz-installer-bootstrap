package link.infra.packwiz.installer.bootstrap;

import org.apache.commons.cli.Options;

import java.io.IOException;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public class LoadJAR {
	
	private static Class<?> mainClass = null;
	private static URLClassLoader classLoader = null;

	private static void loadClass(String path) throws IOException, ClassNotFoundException {
		if (mainClass != null) {
			return;
		}

		Path jarPath = Path.of(path == null ? Bootstrap.JAR_NAME : path)
				.toAbsolutePath()
				.normalize();

		URLClassLoader loader = new URLClassLoader(
				new URL[] { jarPath.toUri().toURL() },
				LoadJAR.class.getClassLoader()
		);

		try {
			Class<?> loadedClass = Class.forName(
					"link.infra.packwiz.installer.Main",
					true,
					loader
			);

			classLoader = loader;
			mainClass = loadedClass;
		} catch (ClassNotFoundException | RuntimeException | Error e) {
			try {
				loader.close();
			} catch (IOException closeException) {
				e.addSuppressed(closeException);
			}

			throw e;
		}
	}
	
	public static boolean addOptions(Options options, String path) {
		try {
			loadClass(path);
			
			Method method = mainClass.getDeclaredMethod("addNonBootstrapOptions", Options.class);
			method.invoke(null, options);
		} catch (Exception e) {
			// Woah that was a lot of errors!
			// Just ignore them, it doesn't matter if there aren't any more options added anyway - it's just a help command.
			return false;
		}
		return true;
	}
	
	public static void start(String[] args, String path) throws Exception {
		loadClass(path);
		
		// Must be casted to Object (not array) because varargs
		mainClass.getConstructor(String[].class).newInstance((Object)args);
	}

	public static String getVersion(String path) {
		try (JarFile jar = new JarFile(path)) {
			Manifest manifest = jar.getManifest();

			if (manifest == null) {
				return null;
			}

			return manifest.getMainAttributes()
					.getValue("Implementation-Version");
		} catch (IOException e) {
			return null;
		}
	}
}
