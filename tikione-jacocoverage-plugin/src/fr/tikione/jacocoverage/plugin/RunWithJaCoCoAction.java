package fr.tikione.jacocoverage.plugin;

import fr.tikione.jacocoexec.analyzer.JaCoCoReportAnalyzer;
import fr.tikione.jacocoexec.analyzer.JaCoCoXmlReportParser;
import fr.tikione.jacocoexec.analyzer.JavaClass;
import static fr.tikione.jacocoverage.plugin.Utils.getProjectId;
import fr.tikione.jacocoverage.plugin.anno.AbstractCoverageAnnotation;
import fr.tikione.jacocoverage.plugin.config.Globals;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.prefs.Preferences;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.tools.ant.module.api.AntProjectCookie;
import org.apache.tools.ant.module.api.AntTargetExecutor;
import org.netbeans.api.project.Project;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionRegistration;
import org.openide.awt.DynamicMenuContent;
import org.openide.execution.ExecutorTask;
import org.openide.filesystems.FileObject;
import org.openide.loaders.DataObject;
import org.openide.util.ContextAwareAction;
import org.openide.util.Exceptions;
import org.openide.util.Lookup;
import org.openide.util.NbPreferences;
import org.xml.sax.SAXException;

/**
 * The "Test with JaCoCoverage" contextual action registration.
 * Start an Ant task with the JaCoCo JavaAgent correctly configured, colorize Java source files and show a coverage report.
 *
 * @author Jonathan Lermitage
 */
@ActionID(category = "Project",
          id = "fr.tikione.jacocoverage.plugin.RunWithJaCoCoAction")
@ActionRegistration(displayName = "Test with JaCoCoverage",
                    lazy = false)
@ActionReference(path = "Projects/Actions",
                 position = 1984,
                 separatorBefore = 1983,
                 separatorAfter = 1985)
public final class RunWithJaCoCoAction extends AbstractAction implements ContextAwareAction {

    private static final long serialVersionUID = 1L;

    public @Override
    void actionPerformed(ActionEvent e) {
    }

    public @Override
    Action createContextAwareInstance(Lookup context) {
        return new ContextAction(context);
    }

    /**
     * The "Test with JaCoCoverage" contextual action.
     */
    private static final class ContextAction extends AbstractAction {

        private static final long serialVersionUID = 1L;

        /** The project the contextual action is called from. */
        private final Project project;

        /**
         * Enable the context action on supported projects only.
         *
         * @param context the context the action is called from.
         */
        public ContextAction(Lookup context) {
            project = context.lookup(Project.class);
            setEnabled(Utils.isProjectSupported(project));
            putValue(DynamicMenuContent.HIDE_WHEN_DISABLED, true);
            putValue(NAME, "Test with JaCoCoverage");
        }

        public @Override
        void actionPerformed(ActionEvent ae) {
            try {
                // Retrieve JaCoCoverage preferences. They contains coloration and JaCoCo JavaAgent customizations.
                Preferences pref = NbPreferences.forModule(RunWithJaCoCoAction.class);
                String antTask = pref.get(Globals.PROP_TEST_ANT_TASK, Globals.DEF_TEST_ANT_TASK);

                // Retrieve proejct properties.
                FileObject prjPropsFo = project.getProjectDirectory().getFileObject("nbproject/project.properties");
                final Properties prjProps = new Properties();
                prjProps.load(prjPropsFo.getInputStream());

                final File xmlreport = Utils.getJacocoXmlReportfile(project);
                final File binreport = Utils.getJacocoBinReportFile(project);
                if (binreport.exists() && !binreport.delete() ||
                        xmlreport.exists() && !xmlreport.delete()) {
                    String msg = "Cannot delete the previous JaCoCo report files, please delete them manually:\n"
                            + binreport.getAbsolutePath() + " and/or\n"
                            + xmlreport.getAbsolutePath();
                    NotifyDescriptor nd = new NotifyDescriptor.Message(msg, NotifyDescriptor.ERROR_MESSAGE);
                    DialogDisplayer.getDefault().notify(nd);
                } else {
                    // Apply JaCoCo JavaAgent customization.
                    String antTaskJavaagentParam = pref.get(Globals.PROP_TEST_ANT_TASK_JAVAAGENT, Globals.DEF_TEST_ANT_TASK_JAVAAGENT)
                            .replace("{pathOfJacocoagentJar}", Utils.getJacocoAgentJar().getAbsolutePath())
                            .replace("{appPackages}", Utils.getProjectJavaPackagesAsStr(project, prjProps, ":", ".*"));

                    FileObject scriptToExecute = project.getProjectDirectory().getFileObject("build", "xml");
                    DataObject dataObj = DataObject.find(scriptToExecute);
                    AntProjectCookie antCookie = dataObj.getLookup().lookup(AntProjectCookie.class);

                    AntTargetExecutor.Env env = new AntTargetExecutor.Env();
                    AntTargetExecutor executor = AntTargetExecutor.createTargetExecutor(env);

                    // Add the customized JaCoCo JavaAgent to the JVM arguments given to the Ant task. The JaCoCo JavaAgent is
                    // appended to the existing list of JVM arguments that is given to the Ant task.
                    Properties targetProps = env.getProperties();
                    String prjJvmArgs = Utils.getProperty(prjProps, "run.jvmargs");
                    targetProps.put("run.jvmargs", prjJvmArgs + "  -javaagent:" + antTaskJavaagentParam);
                    env.setProperties(targetProps);

                    // Launch the Ant task with the JaCoCo JavaAgent.
                    final ExecutorTask execute = executor.execute(antCookie, new String[]{antTask});

                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            // Wait for the end of the Ant task execution. We do it in a new thread otherwise it would
                            // freeze the current one. This is a workaround for a known and old NetBeans bug: the ExecutorTask
                            // object provided by the NetBeans platform is not correctly wrapped.
                            if (0 == execute.result()) {
                                // Load the generated JaCoCo coverage report.
                                String prjDir = Utils.getProjectDir(project) + File.separator;
                                File prjClassesDir = new File(prjDir + Utils.getProperty(prjProps, "build.classes.dir") + File.separator);
                                File prjSourcesDir = new File(prjDir + Utils.getProperty(prjProps, "src.dir") + File.separator);
                                try {
                                    JaCoCoReportAnalyzer.toXmlReport(binreport, xmlreport, prjClassesDir, prjSourcesDir);
                                    final List<JavaClass> coverageData = JaCoCoXmlReportParser.getCoverageData(xmlreport);

                                    // Remove existing highlighting (from a previous coverage task).
                                    AbstractCoverageAnnotation.removeAll(getProjectId(project));

                                    // Show a report in the NetBeans console.
                                    JaCoCoReportAnalyzer.toConsoleReport(coverageData);

                                    // Apply highlighting on each Java source file.
                                    for (final JavaClass jclass : coverageData) {
                                        Utils.colorDoc(project, jclass);
                                    }

                                    // Delete working files.
                                    xmlreport.delete();
                                    binreport.delete();
                                } catch (FileNotFoundException ex) {
                                    Exceptions.printStackTrace(ex);
                                } catch (IOException ex) {
                                    Exceptions.printStackTrace(ex);
                                } catch (ParserConfigurationException ex) {
                                    Exceptions.printStackTrace(ex);
                                } catch (SAXException ex) {
                                    Exceptions.printStackTrace(ex);
                                }
                            }
                        }
                    }).start();
                }
            } catch (IOException ex) {
                Exceptions.printStackTrace(ex);
            } catch (IllegalArgumentException ex) {
                Exceptions.printStackTrace(ex);
            }
        }
    }
}
