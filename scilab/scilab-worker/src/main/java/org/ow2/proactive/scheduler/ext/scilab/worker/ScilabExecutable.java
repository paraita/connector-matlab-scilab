/*
 * ################################################################
 *
 * ProActive Parallel Suite(TM): The Java(TM) library for
 *    Parallel, Distributed, Multi-Core Computing for
 *    Enterprise Grids & Clouds
 *
 * Copyright (C) 1997-2012 INRIA/University of
 *                 Nice-Sophia Antipolis/ActiveEon
 * Contact: proactive@ow2.org or contact@activeeon.com
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; version 3 of
 * the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307
 * USA
 *
 * If needed, contact us to obtain a release under GPL Version 2 or 3
 * or a different license than the AGPL.
 *
 *  Initial developer(s):               The ProActive Team
 *                        http://proactive.inria.fr/team_members.htm
 *  Contributor(s):
 *
 * ################################################################
 * $$PROACTIVE_INITIAL_DEV$$
 */
package org.ow2.proactive.scheduler.ext.scilab.worker;

import org.apache.log4j.Level;
import org.objectweb.proactive.core.util.log.ProActiveLogger;
import org.objectweb.proactive.utils.OperatingSystem;
import org.ow2.proactive.scheduler.common.task.TaskResult;
import org.ow2.proactive.scheduler.common.task.executable.JavaExecutable;
import org.ow2.proactive.scheduler.ext.common.util.FileUtils;
import org.ow2.proactive.scheduler.ext.matsci.common.data.PASolveEnvFile;
import org.ow2.proactive.scheduler.ext.matsci.common.data.PASolveFile;
import org.ow2.proactive.scheduler.ext.matsci.common.data.PASolveZippedFile;
import org.ow2.proactive.scheduler.ext.matsci.worker.util.MatSciEngineConfig;
import org.ow2.proactive.scheduler.ext.scilab.common.PASolveScilabGlobalConfig;
import org.ow2.proactive.scheduler.ext.scilab.common.PASolveScilabTaskConfig;
import org.ow2.proactive.scheduler.ext.scilab.common.exception.ScilabTaskException;
import org.ow2.proactive.scheduler.ext.scilab.worker.util.ScilabEngineConfig;
import org.ow2.proactive.scheduler.ext.scilab.worker.util.ScilabFinder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;


/**
 * ScilabExecutable
 *
 * @author The ProActive Team
 */
public class ScilabExecutable extends JavaExecutable {

    private String tmpDir;

    private static String HOSTNAME;

    static {
        try {
            HOSTNAME = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
        }
    }

    /** The ISO8601 for debug format of the date that precedes the log message */
    private static final SimpleDateFormat ISO8601FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:sss");

    /** For debug purpose see {@link ScilabExecutable#createLogFileOnDebug()} */
    private FileOutputStream debugfos;
    private PrintStream outDebug;

    /** The global configuration */
    private PASolveScilabGlobalConfig paconfig;

    /** The task configuration */
    private PASolveScilabTaskConfig taskconfig;

    /** The SCILAB configuration */
    private ScilabEngineConfig scilabEngineConfig;

    /** The root of the local space and a temporary dir inside */
    private File localSpaceRootDir, tempSubDir;

    /** The connection to SCILAB from scilabcontrol API */
    private ScilabConnection scilabConnection;

    /** The SCILAB script to execute */
    private String script;

    public ScilabExecutable() {
        this.paconfig = new PASolveScilabGlobalConfig();
        this.taskconfig = new PASolveScilabTaskConfig();
    }

    @Override
    public void init(final Map<String, Serializable> args) throws Exception {

        this.tmpDir = System.getProperty("java.io.tmpdir");

        if (!new File(this.tmpDir).canWrite()) {
            throw new RuntimeException("Unable to execute task, TMPDIR : " + this.tmpDir + " is not writable.");
        }

        // Read global configuration
        Object obj = args.get("global_config");
        if (obj != null) {
            this.paconfig = (PASolveScilabGlobalConfig) obj;
        }

        // Create a log file if debug is enabled
        this.createLogFileOnDebug();

        // Read task configuration
        obj = args.get("task_config");
        if (obj != null) {
            this.taskconfig = (PASolveScilabTaskConfig) obj;
        }

        // Read the main script to execute
        this.script = (String) args.get("script");
        if (this.script == null || "".equals(this.script)) {
            throw new IllegalArgumentException("Unable to execute task, no script specified");
        }

        // Initialize SCILAB location
        this.initScilabConfig();

        // Initialize LOCAL SPACE
        this.initLocalSpace();

        // Initialize transfers
        this.initTransferSource();
        this.initTransferEnv();
        this.initTransferInputFiles();
        this.initTransferVariables();
    }

    @Override
    public Serializable execute(final TaskResult... results) throws Throwable {
        if (results != null) {
            for (TaskResult res : results) {
                if (res.hadException()) {
                    throw res.getException();
                }
            }
        }

        if (paconfig.isDebug()) {
            ProActiveLogger.getLogger(ScilabExecutable.class).setLevel(Level.DEBUG);
        }

        final String scilabCmd = this.scilabEngineConfig.getFullCommand();
        this.printLog("Acquiring SCILAB connection using " + scilabCmd);

        // Acquire a connection to SCILAB

        this.scilabConnection = new ScilabConnectionRImpl(this.tmpDir, this.outDebug);

        final String taskId = (String) this.getVariables().get("PA_TASK_ID");
        scilabConnection.acquire(scilabCmd, this.localSpaceRootDir, this.paconfig, this.taskconfig, taskId);

        Serializable result = null;

        try {
            // Execute the SCILAB script and receive the result
            result = this.executeScript();
        } finally {
            this.printLog("Closing SCILAB...");
            this.scilabConnection.release();
            printLog(this.scilabConnection.getOutput(paconfig.isDebug()), true);
            printLog("End of Task");
            this.closeLogFileOnDebug();
        }

        return result;
    }

    public void kill() { // TODO how to clean without kill?
        if (this.scilabConnection != null) {
            // Release the connection
            this.scilabConnection.release();
            this.scilabConnection = null;
        }
    }

    /**
     * Executes both input and main scripts on the engine
     *
     * @throws Throwable
     */
    protected final Serializable executeScript() throws Throwable {

        // Add sources, load workspace and input variables
        scilabConnection.init();

        this.addSources();
        this.loadWorkspace();
        this.loadInputVariables();
        this.loadTopologyNodeNames();

        printLog("Running SCILAB command: " + this.script);

        scilabConnection.evalString(this.script);

        printLog("SCILAB command completed successfully, receiving output... ");

        storeOutputVariable();

        // outputFiles
        transferOutputFiles();

        scilabConnection.launch();

        testOutput();

        return new Boolean(true);
    }

    /*********** PRIVATE METHODS ***********/

    protected MatSciEngineConfig initScilabConfig() throws Exception {
        ScilabEngineConfig conf = (ScilabEngineConfig) ScilabEngineConfig.getCurrentConfiguration();
        if (conf == null) {
            conf = (ScilabEngineConfig) ScilabFinder.getInstance().findMatSci(paconfig.getVersionPref(),
                    paconfig.getVersionRej(), paconfig.getVersionMin(), paconfig.getVersionMax(),
                    paconfig.getVersionArch(), paconfig.isDebug());
            if (conf == null) {
                throw new IllegalStateException("No valid Scilab configuration found, aborting...");
            }

        }
        scilabEngineConfig = conf;
        return scilabEngineConfig;
    }

    private void initLocalSpace() throws Exception {
        final File localSpaceFile = new File(getLocalSpace());
        final URI localSpaceURI = localSpaceFile.toURI();
        final String localSpaceURIstr = localSpaceURI .toString();

        if (!localSpaceFile.exists()) {
            throw new IllegalStateException("Unable to execute task, the local space " + localSpaceURIstr +
                    " doesn't exists");
        }
        if (!localSpaceFile.canRead()) {
            throw new IllegalStateException("Unable to execute task, the local space " + localSpaceURIstr +
                    " is not readable");
        }
        if (!localSpaceFile.canWrite()) {
            throw new IllegalStateException("Unable to execute task, the local space " + localSpaceURIstr +
                    " is not writable");
        }

        // Create a temp dir in the root dir of the local space
        this.localSpaceRootDir = localSpaceFile.getCanonicalFile();
        this.tempSubDir = new File(this.localSpaceRootDir, paconfig.getJobSubDirOSPath()).getCanonicalFile();

        if (!tempSubDir.exists()) {
            tempSubDir.mkdirs();
        }

        // Set the local space of the global configuration
        this.paconfig.setLocalSpace(localSpaceURI);
    }

    private void initTransferSource() throws Exception {

        for (PASolveFile file : taskconfig.getSourceFiles()) {
            if (file instanceof PASolveZippedFile) {
                PASolveZippedFile zippedFile = (PASolveZippedFile) file;
                zippedFile.setRootDirectory(localSpaceRootDir);
                File sourceZip = new File(zippedFile.getFullPathName());
                printLog("Unzipping source files from " + sourceZip);
                if (!sourceZip.exists() || !sourceZip.canRead()) {
                    System.err.println("Error, source zip file cannot be accessed at " + sourceZip);
                    throw new IllegalStateException("Error, source zip file cannot be accessed at " +
                        sourceZip);
                }
                // Uncompress the source files into the temp dir
                if (!FileUtils.unzip(sourceZip, sourceZip.getParentFile())) {
                    System.err.println("Unable to unzip source file " + sourceZip);
                    throw new IllegalStateException("Unable to unzip source file " + sourceZip);
                }
            }
        }

        if (paconfig.isDebug()) {
            printLog("Contents of " + tempSubDir);
            for (File f : tempSubDir.listFiles()) {
                printLog(f.getName());
            }
        }
    }

    private void initTransferEnv() throws Exception {
        if (!paconfig.isTransferEnv()) {
            return;
        }
        PASolveFile file = paconfig.getEnvMatFile();
        file.setRootDirectory(localSpaceRootDir);
    }

    private void initTransferInputFiles() throws Exception {
        // do nothing
    }

    private void initTransferVariables() throws Exception {
        PASolveFile file = taskconfig.getInputVariablesFile();
        file.setRootDirectory(localSpaceRootDir);

        if (taskconfig.getComposedInputVariablesFile() != null) {
            file = taskconfig.getComposedInputVariablesFile();
            file.setRootDirectory(localSpaceRootDir);
        }

    }

    private void addSources() throws Exception {
        if (tempSubDir != null) {
            printLog("Adding to scilabpath sources from " + tempSubDir);
            // Add unzipped source files to the MATALAB path
            scilabConnection.evalString("try;getd('" + tempSubDir + "');catch; end;");
            if (taskconfig.getFunctionVarFiles() != null) {
                for (PASolveFile funcFile : taskconfig.getFunctionVarFiles()) {
                    funcFile.setRootDirectory(this.localSpaceRootDir.getCanonicalPath());
                    scilabConnection.evalString("load('" + funcFile.getFullPathName() + "');");
                }
            }
        }
    }

    private void loadWorkspace() throws Exception {
        if (paconfig.isTransferEnv()) {
            PASolveEnvFile paenv = paconfig.getEnvMatFile();
            paenv.setRootDirectory(this.localSpaceRootDir.getCanonicalPath());
            File envMat = new File(paenv.getFullPathName());
            printLog("Loading workspace from " + envMat);
            if (paconfig.isDebug()) {
                scilabConnection.evalString("disp('Contents of " + envMat + "');");
                scilabConnection.evalString("listvarinfile('" + envMat + "')");
            }
            // Load workspace using SCILAB command
            List<String> globals = paenv.getEnvGlobalNames();
            if (globals != null && globals.size() > 0) {
                String globalstr = "";
                for (String name : globals) {
                    globalstr += "'" + name + "'" + ",";
                }
                globalstr = globalstr.substring(0, globalstr.length() - 1);
                scilabConnection.evalString("global(" + globalstr + ")");
            }
            scilabConnection.evalString("load('" + envMat + "');");
        }
    }

    private void loadInputVariables() throws Exception {

        File inMat = new File(taskconfig.getInputVariablesFile().getFullPathName());

        printLog("Loading input variables from " + inMat);

        scilabConnection.evalString("load('" + inMat + "');");
        if (taskconfig.getComposedInputVariablesFile() != null) {
            File compinMat = new File(taskconfig.getComposedInputVariablesFile().getFullPathName());
            scilabConnection.evalString("load('" + compinMat + "');in=out;clear out;");
        }

    }

    private void loadTopologyNodeNames() throws Exception {
        String urllist = "NODE_URL_LIST = list( ";
        for (String nodeURL : this.getNodesURL()) {
            urllist += "'" + nodeURL + "',";
        }
        urllist = urllist.substring(0, urllist.length() - 1);
        urllist += " );";
        scilabConnection.evalString(urllist);
    }

    private void storeOutputVariable() throws Exception {
        PASolveFile pafile = taskconfig.getOutputVariablesFile();
        pafile.setRootDirectory(localSpaceRootDir.getCanonicalPath());
        File outputFile = new File(pafile.getFullPathName());

        printLog("Storing 'out' variable into " + outputFile);
        scilabConnection.evalString("warning('off');");
        scilabConnection.evalString("save('" + outputFile + "',out);");
        scilabConnection.evalString("warning('on');");

        //if (!outputFile.exists()) {
        //    throw new ScilabTaskException("Unable to store 'out' variable, the output file does not exist");
        //}
    }

    private void transferOutputFiles() throws Exception {
        // do nothing
    }

    private void testOutput() throws Exception {
        printLog("Receiving and testing output...");

        PASolveFile pafile = taskconfig.getOutputVariablesFile();
        pafile.setRootDirectory(localSpaceRootDir.getCanonicalPath());
        File outputFile = new File(pafile.getFullPathName());

        if (!outputFile.exists()) {
            throw new ScilabTaskException("Cannot find output variable file.");
        }

    }

    private void printLog(final String message) {
        printLog(message, false);
    }

    private void printLog(final String message, boolean force) {
        if (!this.paconfig.isDebug() && !force) {
            return;
        }
        final Date d = new Date();
        final String log = "[" + ISO8601FORMAT.format(d) + " " + HOSTNAME + "][" +
            this.getClass().getSimpleName() + "] " + message;

        // In case of non forked mode, the message is skipped after the first line break.
        // To avoid this, lets print line per line
        String[] lines = log.split(System.lineSeparator());
        for (String line  : lines)
        {
            getOut().println(line);
        }

        if (this.outDebug != null) {
            this.outDebug.println(log);
            this.outDebug.flush();
        }

    }


    /** Creates a log file in the java.io.tmpdir if debug is enabled */
    private void createLogFileOnDebug() throws Exception {
        if (!this.paconfig.isDebug()) {
            return;
        }

        final String taskId = (String) this.getVariables().get("PA_TASK_ID");
        final File logFile = new File(this.tmpDir, "ScilabExecutable_" + taskId + ".log");
        if (!logFile.exists()) {
            logFile.createNewFile();
        }

        try {
            this.debugfos = new FileOutputStream(logFile);
            this.outDebug = new PrintStream(this.debugfos);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void closeLogFileOnDebug() {
        try {
            if (this.outDebug != null) {
                this.outDebug.close();
            }
        } catch (Exception e) {
        }
        try {
            if (this.debugfos != null) {
                this.debugfos.close();
            }
        } catch (Exception e) {
        }

    }
}
