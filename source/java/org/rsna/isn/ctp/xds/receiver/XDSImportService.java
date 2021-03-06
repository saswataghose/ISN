/*---------------------------------------------------------------
*  Copyright 2012 by the Radiological Society of North America
*
*  This source software is released under the terms of the
*  RSNA Public License (http://mirc.rsna.org/rsnapubliclicense)
*----------------------------------------------------------------*/

package org.rsna.isn.ctp.xds.receiver;

import java.io.File;
import java.net.URL;
import java.util.Hashtable;
import java.util.List;
import org.apache.log4j.Logger;
import org.rsna.ctp.Configuration;
import org.rsna.ctp.objects.FileObject;
import org.rsna.ctp.pipeline.AbstractPipelineStage;
import org.rsna.ctp.pipeline.ImportService;
import org.rsna.ctp.pipeline.QueueManager;
import org.rsna.ctp.servlets.SummaryLink;
import org.rsna.isn.ctp.ISNRoles;
import org.rsna.isn.ctp.xds.sender.ihe.SOAPSetup;
import org.rsna.server.HttpServer;
import org.rsna.server.ServletSelector;
import org.rsna.util.FileUtil;
import org.rsna.util.StringUtil;
import org.w3c.dom.Element;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * An ImportService that is driven by a servlet to obtain studies from the clearinghouse.
 */
public class XDSImportService extends AbstractPipelineStage implements ImportService {

	static final Logger logger = Logger.getLogger(XDSImportService.class);

	File active = null;
	String activePath = "";
	QueueManager queueManager = null;
	int count = 0;

	DocSetDB docSetDB = null;
	File temp = null;

	String servletContext = "";

	Hashtable<Integer, DocInfoResult> docInfoTable;
	ExecutorService execSvc;
	int maxThreads = 2;

	/**
	 * Construct an XDSImportService.
	 * @param element the XML element from the configuration file,
	 * specifying the configuration of the stage.
	 */
	public XDSImportService(Element element) throws Exception {
		super(element);
		if (root == null) logger.error(name+": No root directory was specified.");

		File queue = new File(root, "queue");
		queueManager = new QueueManager(queue, 0, 0); //use default settings
		active = new File(root, "active");
		active.mkdirs();
		activePath = active.getAbsolutePath();
		queueManager.enqueueDir(active); //requeue any files that are left from an ungraceful shutdown.

		//Get the servlet context.
		servletContext = element.getAttribute("servletContext").trim();

		//Initialize the SOAP configuration.
		//Note: The static init method only initializes if it hasn't already been done,
		//so in configurations with multiple stages that call this method, the multiple
		//calls don't cause a problem.
		SOAPSetup.init();

		//Set up a dummy DocSetDB so RetrieveDocuments will always accept
		//studies triggered by the XDSSenderServlet
		File dbdir = new File(root, "database");
		docSetDB = new DocSetDB(dbdir, true); //true makes it always return false, indicating that the study has not been seen.

		//Make a temp directory for use by RetrieveDocuments
		temp = new File(root, "xds");
		temp.mkdirs();

		//Make a table to temporarily hold document info.
		docInfoTable = new Hashtable<Integer, DocInfoResult>();

		//Get an executor for downloading studies
		execSvc = Executors.newFixedThreadPool( maxThreads );
	}

	/**
	 * Get the size of the import queue.
	 * @return the number of objects in the import queue.
	 */
	public synchronized int getQueueSize() {
		return queueManager.size();
	}

	/**
	 * Start the pipeline stage. This method is called by the pipeline
	 * when it is started. At that time, the Configuration object has
	 * been fully constructed, so it can be interrogated. Don't try to
	 * get the Configuration in the constructor of this class.
	 */
	public void start() {
		Configuration config = Configuration.getInstance();

		//Initialize the XDSConfiguration
		XDSConfiguration.load(element);

		try {
			if (!servletContext.equals("")) {
				//Install the servlet
				HttpServer server = config.getServer();
				ServletSelector selector = server.getServletSelector();
				selector.addServlet("isn-tool", XDSToolServlet.class);
				selector.addServlet(servletContext, XDSReceiverServlet.class);

				//Register the stage using the servletContext
				//Note: this must be done here; not in the constructor.
				config.registerStage(this, servletContext);
			}
			else logger.warn(name+": No servlet context was supplied");

			//Install the ISN roles and ensure that the admin user has them.
			ISNRoles.init();
		}
		catch (Exception ex) {
			logger.warn("Unable to start the stage", ex);
		}
	}

	/**
	 * Get the list of submission sets for a key
	 * @param key the hash key identifying the submission sets
	 */
	public List<DocumentInfo> getSubmissionSets(String key) throws Exception {
		File tempDir = FileUtil.createTempDirectory(temp);
		RetrieveDocuments rd = new RetrieveDocuments(tempDir, docSetDB, key);
		List<DocumentInfo> results = rd.getSubmissionSets();
		for (DocumentInfo di : results) {
			Integer hashInt = new Integer(di.hashCode());
			docInfoTable.put(hashInt, new DocInfoResult(di, key));
		}
		FileUtil.deleteAll(tempDir);
		return results;
	}

	/**
	 * Get a list of studies
	 * @param studies the list of DocInfoResult hashes that identify DocInfoResults
	 * of studies to be retrieved from the clearinghouse.
	 */
	public void getStudies(String key, List<String> studies) {
		StudyDownloader sd = new StudyDownloader(studies);
		execSvc.execute( sd );
	}

	class DocInfoResult {
		long time;
		String key;
		DocumentInfo info;
		public DocInfoResult(DocumentInfo info, String key) {
			this.info = info;
			this.key = key;
			this.time = System.currentTimeMillis();
		}
		public boolean isRecent() {
			return ((System.currentTimeMillis() - time) < 36000000L); //10 hours
		}
		public DocumentInfo getInfo() {
			return info;
		}
		public String getKey() {
			return key;
		}
	}

	private void removeOldResults() {
		Integer[] ints = new Integer[docInfoTable.size()];
		ints = docInfoTable.keySet().toArray(ints);
		for (Integer i : ints) {
			DocInfoResult info = docInfoTable.get(i);
			if (!info.isRecent()) docInfoTable.remove(i);
		}
	}

	class StudyDownloader extends Thread {
		List<String> studies;
		public StudyDownloader(List<String> studies) {
			this.studies = studies;
		}
		public void run() {
			File tempDir = FileUtil.createTempDirectory(temp);
			for (String s : studies) {
				try {
					logger.debug("Starting to download: "+s);
					DocInfoResult result = docInfoTable.get( new Integer(s) );
					RetrieveDocuments rd = new RetrieveDocuments(tempDir, docSetDB, result.getKey());
					if (result != null) {
						DocumentInfo info = result.getInfo();
						rd.getStudy(info,queueManager);
					}
					else logger.debug("...result == null");
					logger.debug("Finished downloading: "+s);
				}
				catch (Exception unable) {
					logger.debug("Unable to download study: "+s, unable);
				}
			}
			FileUtil.deleteAll(tempDir);
			removeOldResults();
		}
	}

	/**
	 * Stop the pipeline stage.
	 */
	public void shutdown() {
		stop = true;
	}

	/**
	 * Determine whether the pipeline stage has shut down.
	 */
	public boolean isDown() {
		return stop;
	}

	/**
	 * Get the next object available for processing.
	 * @return the next object available, or null if no object is available.
	 */
	public synchronized FileObject getNextObject() {
		File file;
		if (queueManager != null) {
			while ((file = queueManager.dequeue(active)) != null) {
				lastFileOut = file;
				lastTimeOut = System.currentTimeMillis();
				FileObject fileObject = FileObject.getInstance(lastFileOut);
				fileObject.setStandardExtension();
				return fileObject;
			}
		}
		return null;
	}

	/**
	 * Release a file from the active directory. Note that other stages in the
	 * pipeline may have moved the file, so it is possible that the file will
	 * no longer exist. This method only deletes the file if it is still in the
	 * active directory.
	 * @param file the file to be released, which must be the original file
	 * supplied by the ImportService.
	 */
	public void release(File file) {
		if ((file != null)
				&& file.exists()
					&& file.getParentFile().getAbsolutePath().equals(activePath)) {
			if (!file.delete()) {
				logger.warn("Unable to release the processed file from the active directory:");
				logger.warn("    file: "+file.getAbsolutePath());
			}
		}
	}

	/**
	 * Get the array of links for display on the summary page.
	 * @param userIsAdmin true if the requesting user has the admin role.
	 * @return the array of links for display on the summary page.
	 */
	public SummaryLink[] getLinks(boolean userIsAdmin) {
		if (!servletContext.equals("")) {
			return new SummaryLink[] {
				new SummaryLink("/"+servletContext, null, "Select Studies for Import", false),
				new SummaryLink("/"+"isn-tool", null, "           Create Key           ", false)
			};
		}
		else return new SummaryLink[0];
	}

	/**
	 * Get HTML text displaying the active status of the stage.
	 * @return HTML text displaying the active status of the stage.
	 */
	public String getStatusHTML() {
		String stageUniqueStatus =
			"<tr><td width=\"20%\">Files received:</td><td>" + count + "</td></tr>"
			+ "<tr><td width=\"20%\">Queue size:</td>"
			+ "<td>" + ((queueManager!=null) ? queueManager.size() : "???") + "</td></tr>";
		return super.getStatusHTML(stageUniqueStatus);
	}

}
