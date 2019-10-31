/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.ctask.general;
// above package assignment temporary pending better aysnch release process

// DATASHARE start
import java.io.BufferedReader;

// package org.dspace.ctask.integrity;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.BitstreamService;
import org.dspace.core.Context;
// DATASHARE end
import org.dspace.curate.AbstractCurationTask;
import org.dspace.curate.Curator;
import org.dspace.curate.Suspendable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ClamScan.java
 *
 * A set of methods to scan using the clamav daemon.
 *
 * TODO: add a check for the inputstream size limit
 *
 * @author wbossons
 */

@Suspendable(invoked = Curator.Invoked.INTERACTIVE)
public class ClamScan extends AbstractCurationTask {
	protected final int DEFAULT_CHUNK_SIZE = 4096;// 2048
	protected final byte[] INSTREAM = "zINSTREAM\0".getBytes();
	protected final byte[] PING = "zPING\0".getBytes();
	protected final byte[] STATS = "nSTATS\n".getBytes();// prefix with z
	protected final byte[] IDSESSION = "zIDSESSION\0".getBytes();
	protected final byte[] END = "zEND\0".getBytes();
	protected final String PLUGIN_PREFIX = "clamav";
	protected final String INFECTED_MESSAGE = "had virus detected.";
	protected final String CLEAN_MESSAGE = "had no viruses detected.";
	protected final String CONNECT_FAIL_MESSAGE = "Unable to connect to virus service - check setup";
	protected final String SCAN_FAIL_MESSAGE = "Error encountered using virus service - check setup";
	protected final String NEW_ITEM_HANDLE = "in workflow";

	private static final Logger log = LoggerFactory.getLogger(ClamScan.class);

	protected String host = null;
	protected int port = 0;
	protected int timeout = 0;
	protected boolean failfast = true;

	protected int status = Curator.CURATE_UNSET;
	protected List<String> results = null;

	protected Socket socket = null;
	protected DataOutputStream dataOutputStream = null;
	protected BitstreamService bitstreamService;

	@Override
	public void init(Curator curator, String taskId) throws IOException {
		super.init(curator, taskId);
		host = configurationService.getProperty(PLUGIN_PREFIX + ".service.host");
		port = configurationService.getIntProperty(PLUGIN_PREFIX + ".service.port");
		timeout = configurationService.getIntProperty(PLUGIN_PREFIX + ".socket.timeout");
		failfast = configurationService.getBooleanProperty(PLUGIN_PREFIX + ".scan.failfast");
		bitstreamService = ContentServiceFactory.getInstance().getBitstreamService();
	}

	@Override
	public int perform(DSpaceObject dso) throws IOException {
		status = Curator.CURATE_SKIP;
		logDebugMessage("The target dso is " + dso.getName());
		if (dso instanceof Item) {
			status = Curator.CURATE_SUCCESS;
			Item item = (Item) dso;
			try {
				openSession();
			} catch (IOException ioE) {
				// no point going further - set result and error out
				closeSession();
				setResult(CONNECT_FAIL_MESSAGE);
				return Curator.CURATE_ERROR;
			}

			try {
				Bundle bundle = itemService.getBundles(item, "ORIGINAL").get(0);
				results = new ArrayList<String>();
				for (Bitstream bitstream : bundle.getBitstreams()) {
					InputStream inputstream = bitstreamService.retrieve(Curator.curationContext(), bitstream);
					logDebugMessage("Scanning " + bitstream.getName() + " . . . ");
					int bstatus = scan(bitstream, inputstream, getItemHandle(item));
					inputstream.close();
					if (bstatus == Curator.CURATE_ERROR) {
						// no point going further - set result and error out
						setResult(SCAN_FAIL_MESSAGE);
						status = bstatus;
						break;
					}
					if (failfast && bstatus == Curator.CURATE_FAIL) {
						status = bstatus;
						break;
					} else if (bstatus == Curator.CURATE_FAIL && status == Curator.CURATE_SUCCESS) {
						status = bstatus;
					}

				}
			} catch (AuthorizeException authE) {
				throw new IOException(authE.getMessage(), authE);
			} catch (SQLException sqlE) {
				throw new IOException(sqlE.getMessage(), sqlE);
			} finally {
				closeSession();
			}

			if (status != Curator.CURATE_ERROR) {
				formatResults(item);
			}
		}
		return status;
	}

	// DATASHARE start
	/**
	 * Is bitstream virus free? Check using TCP.
	 * 
	 * @param bitstream The bitstream to check for virus.
	 * @return Curator.CURATE_SUCCESS if bitstream is virus free?
	 */
	public int virusCheck(Context context, Bitstream bitstream) {
		int bstatus = Curator.CURATE_FAIL;

		try {
			openSession();
			log.info("Scanning " + bitstream.getName() + " . . . ");
			bstatus = scan(bitstream, bitstreamService.retrieve(context, bitstream), NEW_ITEM_HANDLE);
		} catch (IOException ex) {
			log.warn(ex.getMessage());
		} catch (AuthorizeException ex) {
			log.warn(ex.getMessage());
		} catch (SQLException ex) {
			log.warn(ex.getMessage());
		} finally {
			closeSession();
		}

		return bstatus;
	}

	/**
	 * Is file virus free? Check using clamdscan process.
	 * 
	 * @param file The file to check for virus.
	 * @return Curator.CURATE_SUCCESS if file is virus free?
	 */
	public int virusCheck(Context context, File file) {
		int bstatus = Curator.CURATE_FAIL;

		final Pattern PATTERN = Pattern.compile(".*Infected files: 0.*");
		BufferedReader stdInput = null;

		try {
			// spawn clamscan process and wait for result
			Process p = Runtime.getRuntime().exec(new String[] { "clamdscan", file.getPath() });

			p.waitFor();
			int retVal = p.exitValue();
			if (retVal == 0) {
				// good status returned check if pattern is in output
				stdInput = new BufferedReader(new InputStreamReader(p.getInputStream()));

				String s = null;
				while ((s = stdInput.readLine()) != null) {
					Matcher matchHandle = PATTERN.matcher(s);
					if (matchHandle.find()) {
						bstatus = Curator.CURATE_SUCCESS;
						break;
					}
				}
			} else {
				bstatus = Curator.CURATE_ERROR;
			}
		} catch (InterruptedException ex) {
			log.warn(ex.getMessage());
		} catch (IOException ex) {
			log.warn(ex.getMessage());
		} finally {
			if (stdInput != null) {
				try {
					stdInput.close();
				} catch (Exception e) {
					log.warn(e.getMessage());
				}
			}
		}

		if (bstatus != Curator.CURATE_SUCCESS) {
			log.warn("*** File " + file + " has failed virus check. status = " + bstatus);
		}

		return bstatus;
	}
	// DATASHARE end

	/**
	 * openSession
	 *
	 * This method opens a session.
	 */

	protected void openSession() throws IOException {
		socket = new Socket();
		try {
			logDebugMessage("Connecting to " + host + ":" + port);
			socket.connect(new InetSocketAddress(host, port));
		} catch (IOException e) {
			log.error("Failed to connect to clamd . . .", e);
			throw (e);
		}
		try {
			socket.setSoTimeout(timeout);
		} catch (SocketException e) {
			log.error("Could not set socket timeout . . .  " + timeout + "ms", e);
			throw (new IOException(e));
		}
		try {
			dataOutputStream = new DataOutputStream(socket.getOutputStream());
		} catch (IOException e) {
			log.error("Failed to open OutputStream . . . ", e);
			throw (e);
		}

		try {
			dataOutputStream.write(IDSESSION);
		} catch (IOException e) {
			log.error("Error initiating session with IDSESSION command . . . ", e);
			throw (e);
		}
	}

	/**
	 * closeSession
	 *
	 * Close the IDSESSION in CLAMD
	 *
	 *
	 */
	protected void closeSession() {
		if (dataOutputStream != null) {
			try {
				dataOutputStream.write(END);
			} catch (IOException e) {
				log.error("Exception closing dataOutputStream", e);
			}
		}
		try {
			logDebugMessage("Closing the socket for ClamAv daemon . . . ");
			socket.close();
		} catch (IOException e) {
			log.error("Exception closing socket", e);
		}
	}

	/** A buffer to hold chunks of an input stream to be scanned for viruses. */
	final byte[] buffer = new byte[DEFAULT_CHUNK_SIZE];

	/**
	 * Issue the INSTREAM command and return the response to and from the clamav
	 * daemon.
	 *
	 * @param bitstream   the bitstream for reporting results
	 * @param inputstream the InputStream to read
	 * @param itemHandle  the item handle for reporting results
	 * @return a ScanResult representing the server response
	 */
	protected int scan(Bitstream bitstream, InputStream inputstream, String itemHandle) {
		try {
			dataOutputStream.write(INSTREAM);
		} catch (IOException e) {
			log.error("Error writing INSTREAM command", e);
			return Curator.CURATE_ERROR;
		}
		int read = DEFAULT_CHUNK_SIZE;
		while (read == DEFAULT_CHUNK_SIZE) {
			try {
				read = inputstream.read(buffer);
			} catch (IOException e) {
				log.error("Failed attempting to read the InputStream", e);
				return Curator.CURATE_ERROR;
			}
			if (read == -1) {
				break;
			}
			try {
				dataOutputStream.writeInt(read);
				dataOutputStream.write(buffer, 0, read);
			} catch (IOException e) {
				log.error("Could not write to the socket", e);
				return Curator.CURATE_ERROR;
			}
		}
		try {
			dataOutputStream.writeInt(0);
			dataOutputStream.flush();
		} catch (IOException e) {
			log.error("Error writing zero-length chunk to socket", e);
			return Curator.CURATE_ERROR;
		}
		try {
			read = socket.getInputStream().read(buffer);

		} catch (IOException e) {
			log.error("Error reading result from socket", e);
			return Curator.CURATE_ERROR;
		}

		if (read > 0) {
			String response = new String(buffer, 0, read);
			logDebugMessage("Response: " + response);
			if (response.contains("FOUND")) {
				String itemMsg = "item - " + itemHandle + ": ";
				String bsMsg = "bitstream - " + bitstream.getName() + ": SequenceId - " + bitstream.getSequenceID()
						+ ": infected";
				report(itemMsg + bsMsg);
				results.add(bsMsg);
				return Curator.CURATE_FAIL;
			} else {
				return Curator.CURATE_SUCCESS;
			}
		}
		return Curator.CURATE_ERROR;
	}

	protected void formatResults(Item item) throws IOException {
		StringBuilder sb = new StringBuilder();
		sb.append("Item: ").append(getItemHandle(item)).append(" ");
		if (status == Curator.CURATE_FAIL) {
			sb.append(INFECTED_MESSAGE);
			int count = 0;
			for (String scanresult : results) {
				sb.append("\n").append(scanresult).append("\n");
				count++;
			}
			sb.append(count).append(" virus(es) found. ").append(" failfast: ").append(failfast);
		} else {
			sb.append(CLEAN_MESSAGE);
		}
		setResult(sb.toString());
	}

	protected String getItemHandle(Item item) {
		String handle = item.getHandle();
		return (handle != null) ? handle : NEW_ITEM_HANDLE;
	}

	protected void logDebugMessage(String message) {
		if (log.isDebugEnabled()) {
			log.debug(message);
		}
	}
}
