
package link.infra.packwiz.installer.bootstrap;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.URLConnection;

class ConnMonitorInputStream extends InputStream {
	private final URLConnection conn;
	private final ProgressMonitor mon;

	private InputStream in;
	private long size = -1;
	private long bytesRead = 0;
	private long lastMillisUpdated = 0;
	private boolean closed = false;

	private static final int PROGRESS_MAX = 1000;
	private static final long UPDATE_INTERVAL = 100;

	public ConnMonitorInputStream(URLConnection conn, String message, String note) throws IOException {
		this.conn = conn;

		ProgressMonitor[] monitor = new ProgressMonitor[1];

		runOnEDT(() -> {
			monitor[0] = new ProgressMonitor(null, message, note, 0, PROGRESS_MAX);
			monitor[0].setMillisToDecideToPopup(1);
			monitor[0].setMillisToPopup(1);
		});

		mon = monitor[0];
	}

	private static void runOnEDT(Runnable action) throws IOException {
		if (EventQueue.isDispatchThread()) {
			action.run();
			return;
		}

		try {
			EventQueue.invokeAndWait(action);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new InterruptedIOException("Interrupted while updating progress monitor");
		} catch (InvocationTargetException e) {
			throw new IOException("Failed to update progress monitor", e.getCause());
		}
	}

	private void setup() throws IOException {
		if (closed) {
			throw new IOException("Stream is closed");
		}

		if (in != null) {
			return;
		}

		try {
			size = conn.getContentLengthLong();
			in = conn.getInputStream();

			updateProgress(false);
		} catch (IOException e) {
			try {
				closeMonitor();
			} catch (IOException closeException) {
				e.addSuppressed(closeException);
			}

			throw e;
		}
	}

	private void updateProgress(boolean completed) throws IOException {
		long now = System.currentTimeMillis();

		if (!completed && now - lastMillisUpdated < UPDATE_INTERVAL) {
			return;
		}

		lastMillisUpdated = now;

		runOnEDT(() -> {
			if (size > 0) {
				int progress = completed
						? PROGRESS_MAX
						: (int) Math.min(PROGRESS_MAX, (double) bytesRead / size * PROGRESS_MAX);

				mon.setProgress(progress);
			} else {
				mon.setNote(bytesRead + " bytes downloaded");
			}

			if (mon.isCanceled()) {
				// Cancellation is checked by the reading thread.
			}
		});

		checkCancelled();
	}

	private void checkCancelled() throws IOException {
		boolean[] cancelled = new boolean[1];

		runOnEDT(() -> cancelled[0] = mon.isCanceled());

		if (cancelled[0]) {
			throw new InterruptedIOException("Download cancelled!");
		}
	}

	private void closeMonitor() throws IOException {
		runOnEDT(mon::close);
	}

	@Override
	public int available() throws IOException {
		return in == null ? 0 : in.available();
	}

	@Override
	public int read() throws IOException {
		setup();

		int result = in.read();

		if (result != -1) {
			bytesRead++;
			updateProgress(false);
		} else {
			updateProgress(true);
		}

		return result;
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		setup();

		int result = in.read(b, off, len);

		if (result > 0) {
			bytesRead += result;
			updateProgress(false);
		} else if (result == -1) {
			updateProgress(true);
		}

		return result;
	}

	@Override
	public void close() throws IOException {
		if (closed) {
			return;
		}

		closed = true;

		IOException error = null;

		if (in != null) {
			try {
				in.close();
			} catch (IOException e) {
				error = e;
			}
		}

		try {
			closeMonitor();
		} catch (IOException e) {
			if (error == null) {
				error = e;
			} else {
				error.addSuppressed(e);
			}
		}

		if (error != null) {
			throw error;
		}
	}
}
