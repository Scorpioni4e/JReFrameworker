package jreframeworker.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import jreframeworker.Activator;
import jreframeworker.builder.JReFrameworkerBuilder;
import jreframeworker.builder.JReFrameworkerNature;
import jreframeworker.common.RuntimeUtils;
import jreframeworker.log.Log;

import org.eclipse.core.filesystem.URIUtil;
import org.eclipse.core.internal.events.BuildCommand;
import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.launching.LibraryLocation;

@SuppressWarnings("restriction")
public class JReFrameworker {

	public static final String RUNTIMES_DIRECTORY = "runtimes";
	public static final String ORIGINAL_RUNTIMES_DIRECTORY = ".original-runtimes"; // hidden directory
	public static final String ANNOTATIONS_DIRECTORY = "annotations";
	public static final String SOURCE_DIRECTORY = "src";
	public static final String BINARY_DIRECTORY = "bin";
	public static final String JRE_FRAMEWORKER_ANNOTATIONS_JAR = "JReFrameworkerAnnotations.jar";
	
	// references: 
	// https://sdqweb.ipd.kit.edu/wiki/JDT_Tutorial:_Creating_Eclipse_Java_Projects_Programmatically
	// https://eclipse.org/articles/Article-Builders/builders.html
	// http://www.programcreek.com/java-api-examples/index.php?api=org.eclipse.core.internal.events.BuildCommand
	public static IStatus createProjectWithDefaultRuntime(String projectName, IPath projectPath, IProgressMonitor monitor) throws CoreException, IOException, URISyntaxException {
		IProject project = null;
		
		try {
			monitor.beginTask("Create JReFrameworker Runtime Project", 2);
			
			// create the empty eclipse project
			monitor.setTaskName("Creating Eclipse project...");
			project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
			File projectDirectory = new File(projectPath.toFile().getCanonicalPath() + File.separatorChar + project.getName()).getCanonicalFile();
			File runtimesDirectory = new File(projectDirectory.getCanonicalPath() + File.separatorChar + RUNTIMES_DIRECTORY);
			runtimesDirectory.mkdirs();
			File originalRuntimesDirectory = new File(projectDirectory.getCanonicalPath() + File.separatorChar + ORIGINAL_RUNTIMES_DIRECTORY);
			originalRuntimesDirectory.mkdirs();
			IJavaProject jProject = createProject(projectName, projectPath, monitor, project);
			monitor.worked(1);
			if (monitor.isCanceled()){
				return Status.CANCEL_STATUS;
			}
			
			// copy runtimes and configure project classpath
			monitor.setTaskName("Configuring project classpath...");
			configureProjectClasspath(jProject);
			monitor.worked(1);
			if (monitor.isCanceled()){
				return Status.CANCEL_STATUS;
			}
			
			return Status.OK_STATUS;
		} finally {
			if (project != null && project.exists()){
				project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
			}
			monitor.done();
		}
	}

	private static void configureProjectClasspath(IJavaProject jProject) throws CoreException, JavaModelException, IOException, URISyntaxException {
		// create bin folder
		IFolder binFolder = jProject.getProject().getFolder(BINARY_DIRECTORY);
		binFolder.create(false, true, null);
		jProject.setOutputLocation(binFolder.getFullPath(), null);
		
		// add the runtime libraries
		cloneDefaultRuntimeLibraries(jProject);
		
		// create source folder
		IFolder sourceFolder = jProject.getProject().getFolder(SOURCE_DIRECTORY);
		sourceFolder.create(false, true, null);
		
		// add source folder to project class entries
		addClasspathEntry(jProject, sourceFolder);
		
		Log.info("Successfully created JReFrameworker project [" + jProject.getProject().getName() + "]");
	}

	private static IJavaProject createProject(String projectName, IPath projectPath, IProgressMonitor monitor, IProject project) throws CoreException {
		IProjectDescription projectDescription = project.getWorkspace().newProjectDescription(project.getName());
		URI location = getProjectLocation(projectName, projectPath);
		projectDescription.setLocationURI(location);
		
		// make this a JReFrameworker project
		projectDescription.setNatureIds(new String[] { JavaCore.NATURE_ID, JReFrameworkerNature.NATURE_ID });

		// build first with Java compiler then JReFramewoker bytecode operations
		BuildCommand javaBuildCommand = new BuildCommand();
		javaBuildCommand.setBuilderName(JavaCore.BUILDER_ID);
		BuildCommand jrefBuildCommand = new BuildCommand();
		jrefBuildCommand.setBuilderName(JReFrameworkerBuilder.BUILDER_ID);
		projectDescription.setBuildSpec(new ICommand[]{ javaBuildCommand, jrefBuildCommand});

		// create and open the Eclipse project
		project.create(projectDescription, null);
		IJavaProject jProject = JavaCore.create(project);
		project.open(new NullProgressMonitor());
		return jProject;
	}

	private static void addClasspathEntry(IJavaProject jProject, IResource resource) throws JavaModelException {
		IPackageFragmentRoot root = jProject.getPackageFragmentRoot(resource);
		IClasspathEntry[] oldEntries = jProject.getRawClasspath();
		IClasspathEntry[] newEntries = new IClasspathEntry[oldEntries.length + 1];
		System.arraycopy(oldEntries, 0, newEntries, 0, oldEntries.length);
		newEntries[oldEntries.length] = JavaCore.newSourceEntry(root.getPath());
		jProject.setRawClasspath(newEntries, null);
	}
	
	private static void cloneDefaultRuntimeLibraries(IJavaProject jProject) throws IOException, JavaModelException, URISyntaxException {
		
		File runtimesDirectory = jProject.getProject().getFolder(RUNTIMES_DIRECTORY).getLocation().toFile();
		File originalRuntimesDirectory = jProject.getProject().getFolder(ORIGINAL_RUNTIMES_DIRECTORY).getLocation().toFile();
		
		// add the default JVM classpath (assuming translator uses the same jvm libraries)
		IVMInstall vmInstall = JavaRuntime.getDefaultVMInstall();
		LinkedList<File> libraries = new LinkedList<File>();
		for (LibraryLocation element : JavaRuntime.getLibraryLocations(vmInstall)) {
			File library = JavaCore.newLibraryEntry(element.getSystemLibraryPath(), null, null).getPath().toFile().getCanonicalFile();
			File runtimesCopy = new File(runtimesDirectory.getCanonicalPath() + File.separatorChar + library.getName());
			RuntimeUtils.copyFile(library, runtimesCopy);
			File originalRuntimesCopy = new File(originalRuntimesDirectory.getCanonicalPath() + File.separatorChar + library.getName());
			RuntimeUtils.copyFile(library, originalRuntimesCopy);
			libraries.add(runtimesCopy);
		}
		
		// add the jreframeworker operations jar to project and the classpath
		final String annotationsJarFilename = JRE_FRAMEWORKER_ANNOTATIONS_JAR;
		String annotationsJarPath = ANNOTATIONS_DIRECTORY + "/" + annotationsJarFilename;
		// see http://stackoverflow.com/q/23825933/475329 for logic of getting bundle resource
		URL fileURL = Activator.getContext().getBundle().getEntry(annotationsJarPath);
		URL resolvedFileURL = FileLocator.toFileURL(fileURL);
		// need to use the 3-arg constructor of URI in order to properly escape file system chars
		URI resolvedURI = new URI(resolvedFileURL.getProtocol(), resolvedFileURL.getPath(), null);
		InputStream annotationsJarInputStream = resolvedURI.toURL().openConnection().getInputStream();
		if(annotationsJarInputStream == null){
			throw new RuntimeException("Could not locate: " + annotationsJarPath);
		}
		File annotationsLibDirectory = new File(jProject.getProject().getLocation().toFile().getCanonicalPath() + File.separatorChar + ANNOTATIONS_DIRECTORY);
		annotationsLibDirectory.mkdirs();
		File annotationsJar = new File(annotationsLibDirectory.getCanonicalPath() + File.separatorChar + JRE_FRAMEWORKER_ANNOTATIONS_JAR);
		Files.copy(annotationsJarInputStream, annotationsJar.toPath());
		libraries.add(annotationsJar);
		
		// add the project libraries to the project classpath
		List<IClasspathEntry> entries = new ArrayList<IClasspathEntry>();
		for(File projectJar : libraries){
			String projectJarCanonicalPath = projectJar.getCanonicalPath();
			String projectCanonicalPath = jProject.getProject().getLocation().toFile().getCanonicalPath();
			String projectJarBasePath = projectJarCanonicalPath.substring(projectJarCanonicalPath.indexOf(projectCanonicalPath));
			String projectJarParentCanonicalPath = projectJar.getCanonicalPath();
			String projectJarParentBasePath = projectJarParentCanonicalPath.substring(projectJarParentCanonicalPath.indexOf(projectCanonicalPath));
			entries.add(JavaCore.newLibraryEntry(new Path(projectJarBasePath), null, new Path(projectJarParentBasePath)));
		}
		
		// set the class path
		jProject.setRawClasspath(entries.toArray(new IClasspathEntry[entries.size()]), null);
	}

//	private static void linkDefaultRuntimeLibraries(IJavaProject jProject) throws JavaModelException {
//		List<IClasspathEntry> entries = new ArrayList<IClasspathEntry>();
//		IVMInstall vmInstall = JavaRuntime.getDefaultVMInstall();
//		LibraryLocation[] locations = JavaRuntime.getLibraryLocations(vmInstall);
//		for (LibraryLocation element : locations) {
//		 entries.add(JavaCore.newLibraryEntry(element.getSystemLibraryPath(), null, null));
//		}
//		//add libs to project class path
//		jProject.setRawClasspath(entries.toArray(new IClasspathEntry[entries.size()]), null);
//	}

	private static URI getProjectLocation(String projectName, IPath projectPath) {
		URI location = null;
		if (projectPath != null){
			location = URIUtil.toURI(projectPath);
		}
		if (location != null && ResourcesPlugin.getWorkspace().getRoot().getLocationURI().equals(location)) {
			location = null;
		} else {
			location = URIUtil.toURI(URIUtil.toPath(location) + File.separator + projectName);
		}
		return location;
	}

}