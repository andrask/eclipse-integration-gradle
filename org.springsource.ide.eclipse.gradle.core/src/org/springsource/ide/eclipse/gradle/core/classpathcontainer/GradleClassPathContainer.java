/*******************************************************************************
 * Copyright (c) 2012, 2014 Pivotal Software, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * Pivotal Software, Inc. - initial API and implementation
 *******************************************************************************/
package org.springsource.ide.eclipse.gradle.core.classpathcontainer;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.internal.core.JavaProject;
import org.gradle.tooling.model.eclipse.EclipseProject;
import org.springsource.ide.eclipse.gradle.core.ClassPath;
import org.springsource.ide.eclipse.gradle.core.GradleCore;
import org.springsource.ide.eclipse.gradle.core.GradleDependencyComputer;
import org.springsource.ide.eclipse.gradle.core.GradleProject;
import org.springsource.ide.eclipse.gradle.core.GradleSaveParticipant;
import org.springsource.ide.eclipse.gradle.core.ProjectOpenCloseListener;
import org.springsource.ide.eclipse.gradle.core.util.ExceptionUtil;
import org.springsource.ide.eclipse.gradle.core.util.GradleRunnable;
import org.springsource.ide.eclipse.gradle.core.util.JobUtil;
import org.springsource.ide.eclipse.gradle.core.wtp.WTPUtil;


/**
 * @author Kris De Volder
 */
@SuppressWarnings("restriction")
public class GradleClassPathContainer implements IClasspathContainer /*, Cloneable*/ {
	
	/**
	 * Listener that gets called when classpath container is refreshed. 
	 * The main purpose of this listener is to allow test code to wait for refreshes
	 * before proceeding with checking assertions about a classpath container they caused to
	 * be refreshed.
	 */
	public interface IRefreshListener {
		void classpathContainerRefreshed();
	}

	public static final String ERROR_MARKER_ID = "org.springsource.ide.eclipse.gradle.core.classpathcontainer";
	private static final String GRADLE_CLASSPATHCONTAINER_KEY = "gradle.classpathcontainer";

	public static final boolean DEBUG = false; //(""+Platform.getLocation()).contains("kdvolder");
	public static final boolean S_DEBUG = false;
	
	private static final void debug(String msg) {
		if (DEBUG) {
			System.out.println("GradleClassPathContainer: "+msg);
		}
	}

	private static final void sdebug(String msg) {
		if (S_DEBUG) {
			System.out.println("GradleClassPathContainer: "+msg);
		}
	}
	
	public static final String ID = "org.springsource.ide.eclipse.gradle.classpathcontainer";
	
	private GradleProject project;
	private IPath path;

	private Job job;

	/**
	 * This field remembers what model the persisted entries came from (this will only
	 * be known if the entries where created during the current session since models
	 * themselves do not persist across sessions).
	 */
	private EclipseProject oldModel = null;
	private IClasspathEntry[] persistedEntries;
	private IRefreshListener refreshListener;
	private ProjectOpenCloseListener openCloseListener;
	
	public void addRefreshListener(IRefreshListener l) {
		Assert.isLegal(refreshListener==null);
		refreshListener = l;
	}

	public void removeRefreshListener(IRefreshListener l) {
		if (refreshListener==l) {
			refreshListener=null;
		}
	}
	
	/**
	 * Creates an uninitialised {@link GradleClassPathContainer}. If displayed in the UI it
	 * will have no entries.
	 */
	public GradleClassPathContainer(IPath path, GradleProject project) {
		this.project = project;
		this.path = path;
	}

	/**
	 * Request an asynchronous update of this class path container. This causes the class path container to
	 * return to uninitialised state and ensures a job is scheduled to initialise it later.
	 * It is acceptable to request multiple updates, only one update job should be scheduled.
	 * @return Reference to the job that is doing the update, in case caller cares to wait for it to finish.
	 */
	public synchronized Job requestUpdate(boolean popupProgress) {
		if (job!=null) {
			return job; // A job is already scheduled. Don't schedule another job!
		}

		GradleRunnable runnable = new GradleRunnable("Update Gradle Classpath for "+project.getName()) {
			
			@Override
			public void doit(IProgressMonitor monitor) throws Exception {
				monitor.beginTask("Initializing Gradle Classpath Container", IProgressMonitor.UNKNOWN);
				
				try {
					project.getGradleModel(monitor); // Forces initialisation of the model.
					notifyJDT();
				} catch (Exception e) {
					throw ExceptionUtil.coreException("Error while initializing classpath container");
				} finally {
					monitor.done();
					job = null;
				}
			}
		};
		job = popupProgress ? JobUtil.userJob(runnable) : JobUtil.schedule(runnable);
		return job;
	}

	public synchronized IClasspathEntry[] getClasspathEntries() {
		ensureOpenCloseListener();
		GradleDependencyComputer dependencyComputer = project.getDependencyComputer();
		debug("getClassPathEntries called");
		try {
			EclipseProject gradleModel = project.getGradleModel();
			if (gradleModel!=null) {
				if (oldModel==gradleModel) {
					IClasspathEntry[] persisted = getPersistedEntries();
					if (persisted!=null) {
						return persisted;
					}
				}
				IClasspathEntry[] entries = dependencyComputer.getClassPath(gradleModel).toArray();
				setPersistedEntries(entries);
				oldModel = gradleModel;
				return entries;
			}
		} catch (CoreException e) {
			GradleCore.log(e);
		} catch (FastOperationFailedException e) {
			debug("Failed to quickly get Gradle model");
		}
		//We reach here if we could not quickly get the container contents from Gradle we have one more thing to try
		IClasspathEntry[] persistedEntries = getPersistedEntries();
		if (persistedEntries!=null) {
			debug("returning "+persistedEntries.length+" entries");
			return persistedEntries;
		}
		debug("Returning *empty* list of entries");
		requestUpdate(false);
		return new IClasspathEntry[] {
		};
	}
	
	/**
	 * Ensures that open close listener is registered to respond to openening and closing of projects
	 * and update remapped jars in classpath container
	 * 
	 */
	private void ensureOpenCloseListener() {
		if (openCloseListener==null) {
			openCloseListener = new ProjectOpenCloseListener() {
				@Override
				public void projectOpened(IProject project) {
					quickRefreshAllContainers();
				}
				
				@Override
				public void projectClosed(IProject project) {
					quickRefreshAllContainers();
				}
			};
			GradleCore.getInstance().addOpenCloseListener(openCloseListener);
		}
	}

	public String getDescription() {
		String desc = "Gradle Dependencies";
		if (!isInitialized()) {
			if (getPersistedEntries()==null) {
				desc += " (uninitialized)";
			} else {
				desc += " (persisted)";
			}
		}
		return desc;
	}

	public int getKind() {
		return IClasspathContainer.K_APPLICATION;
	}

	public IPath getPath() {
		return path;
	}
	
	public boolean isInitialized() {
		try {
			project.getGradleModel(EclipseProject.class);
			return true;
		} catch (FastOperationFailedException e) {
		} catch (CoreException e) {
			GradleCore.log(e);
		}
		return false;
	}

	/**
	 * Poke JDT when the container became fully initialised.
	 */
	void notifyJDT() {
		debug("notifyJDT");
//		setJDTClassPathContainer(project, path, 
//					isInitialized()? this : null);
//		System.err.println("JDT notified: "+this);
//		JavaModelManager.getJavaModelManager().getClasspathContainer(getPath(), project);
		setJDTClassPathContainer(project.getJavaProject(), path, null); // Makes JDT get our class path initialiser to run again.
		notifyRefreshListener();
	}

	private void notifyRefreshListener() {
		if (refreshListener!=null) {
			refreshListener.classpathContainerRefreshed();
		}
	}

	public static void setJDTClassPathContainer(IJavaProject project, IPath path, GradleClassPathContainer container) {
		if (project!=null) { 
			GradleClasspathContainerInitializer.debug("setting container on "+project.getElementName()+" to "+container);
			//project may be null, if project got deleted since the refresh got started... 
			try {
				JavaCore.setClasspathContainer(path,
						new IJavaProject[] {project},  
						//					new IClasspathContainer[] {getClone(container)}, //Clone it to make sure JDT pays attention (needs to see a 'changed' object).
						new IClasspathContainer[] {container},
						new NullProgressMonitor());
			} catch (JavaModelException e) {
				GradleCore.log(e);
				//		} catch (CloneNotSupportedException e) {
				//			GradleCore.log(e);
			}
		}
	}


	/**
	 * Refresh classpath entries in all containers in the workspace quickly (i.e. without invalidating
	 * the cached gradle models and rebuilding them). This is useful when the entries need to be
	 * recomputed because a project was opened / closed and so jar -> gradle or 
	 */
	private void quickRefreshAllContainers() {
		//TODO mechanism to avoid repeatedly scheduling many of these jobs in parallel.
		final Collection<GradleProject> projects = GradleCore.getGradleProjects();
		if (!projects.isEmpty()) {
			JobUtil.schedule(new GradleRunnable("Refresh Gradle classpath containers") {
				@Override
				public void doit(IProgressMonitor mon) throws Exception {
					mon.beginTask("Refresh Gradle Classpath Containers", projects.size());
					for (GradleProject p : projects) {
						GradleClassPathContainer classpath = p.getClassPathcontainer();
						if (classpath!=null) {
							mon.subTask("Refresh "+p.getName());
							clearPersistedEntries();
							notifyJDT();
						}
						mon.worked(1);
					}
				}
			});
		}
	}
	
	@Override
	public String toString() {
		StringBuffer out = new StringBuffer(getDescription());
//		+ "{\n");
//		for (IClasspathEntry e : getClasspathEntries()) {
//			out.append("   "+e.getPath()+"\n");
//		}
//		out.append("}");
		return out.toString();
	}
	

	/**
	 * Ensures that entries will be recomputed next time around
	 */
	private void clearPersistedEntries() {
		setPersistedEntries(null);
		project.getDependencyComputer().clearPersistedEntries();
	}

	/**
	 * Returns true if the {@link GradleClassPathContainer} is on the project's classpath.
	 */
	public static boolean isOnClassPath(IJavaProject project) {
		try {
			IClasspathEntry[] classpath = project.getRawClasspath();
			for (IClasspathEntry e : classpath) {
				if (isGradleContainerEntry(e)) {
					return true;
				}
			}
		} catch (JavaModelException e) {
			GradleCore.log(e);
		}
		return false;
	}

	/**
	 * Removes {@link GradleClassPathContainer} entries from the project's classpath.
	 */
	public static void removeFrom(IJavaProject javaProject, IProgressMonitor mon) throws JavaModelException {
		GradleCore.create(javaProject).disposeClassPathcontainer();
		IClasspathEntry[] classpath = javaProject.getRawClasspath();
		ClassPath newClasspath = new ClassPath(GradleCore.create(javaProject));
		for (IClasspathEntry e : classpath) {
			if (!isGradleContainerEntry(e)) {
				newClasspath.add(e);
			}
		}
		newClasspath.setOn(javaProject, mon);
		new MarkerMaker(GradleCore.create(javaProject), ERROR_MARKER_ID).schedule(); //This should erase any existing markers.
	}

	public static boolean isGradleContainerEntry(IClasspathEntry e) {
		return e!=null && e.getEntryKind()==IClasspathEntry.CPE_CONTAINER && e.getPath().segment(0).equals(ID);
	}

	/**
	 * Adds a {@link GradleClassPathContainer} entry to the project's classpath.
	 * @param mon 
	 * @throws JavaModelException 
	 */
	public static void addTo(IJavaProject project, IProgressMonitor mon) throws JavaModelException {
		mon.beginTask("Add classpath container", 10);
		try {
			mon.worked(1);
			if (!isOnClassPath(project)) {
				boolean export = GradleCore.getInstance().getPreferences().isExportDependencies();
				sdebug("Adding... to "+project.getElementName());
				//Only add it if itsn't there yet
				ClassPath classpath = new ClassPath(GradleCore.create(project));
				
				//			classpath.add(JavaCore.newContainerEntry(new Path(ID)));
				classpath.DEBUG = S_DEBUG;
				classpath.add(WTPUtil.addToDeploymentAssembly(project, JavaCore.newContainerEntry(new Path(ID), export)));
				classpath.addAll(Arrays.asList(project.getRawClasspath()));
//						GlobalSettings.exportClasspathContainer)));
				classpath.removeLibraryEntries();
				classpath.removeProjectEntries();
				classpath.setOn(project, new SubProgressMonitor(mon, 9));
				GradleCore.create(project).getClassPathcontainer().refreshMarkers();
				sdebug("Done Adding to "+project.getElementName());
			} else {
				sdebug("NOT adding (already there) "+project.getElementName());
			}
		} finally {
			mon.done();
		}
	}

	private void refreshMarkers() {
		oldModel = null; //Forces recomputation of entries on next 'getClasspathEntries
		getClasspathEntries(); //The markers are now refreshed as a side effect.
	}

	/**
	 * This method may be used during initialisation, if the container is initialised using 
	 * a persisted set of entries from the previous run. Persisted entries will only be
	 * returned if there's no GradleModel to return a properly computed set of entries.
	 */
	public void setPersistedEntries(IClasspathEntry[] persistedEntries) {
		this.persistedEntries = persistedEntries;
		IProject eclipseProject = project.getProject();
		if (eclipseProject!=null) { // prpject was probably deleted, so no eclipse project associated anymore
			GradleSaveParticipant store = GradleSaveParticipant.getInstance();
			store.put(eclipseProject, GRADLE_CLASSPATHCONTAINER_KEY, encode(persistedEntries));
		}
	}

	
	private IClasspathEntry[] getPersistedEntries() {
		if (persistedEntries!=null) {
			debug("In memory persisted");
			return persistedEntries;
		} else {
			debug("Decoding persisted");
			IProject eclipseProject = project.getProject();
			GradleSaveParticipant store = GradleSaveParticipant.getInstance();
			return persistedEntries = decode(store.get(eclipseProject, GRADLE_CLASSPATHCONTAINER_KEY));
		}
	}

	private IClasspathEntry[] decode(Serializable serializable) {
		JavaProject jp = (JavaProject) project.getJavaProject();
		if (serializable!=null) {
			String[] encoded = (String[]) serializable;
			IClasspathEntry[] decoded = new IClasspathEntry[encoded.length];
			for (int i = 0; i < decoded.length; i++) {
				decoded[i] = jp.decodeClasspathEntry(encoded[i]);
			}
			return decoded;
		}
		return null;
	}
	
	private Serializable encode(IClasspathEntry[] entries) {
		JavaProject jp = (JavaProject) project.getJavaProject();
		if (entries!=null) {
			String[] encoded = new String[entries.length];
			for (int i = 0; i < encoded.length; i++) {
				encoded[i] = jp.encodeClasspathEntry(entries[i]);
			}
			return encoded;
		}
		return null;
	}

	/**
	 * This method is here to facilitate testing. Classpath container refreshes, removals and
	 * additions also trigger marker updates but these updates are not necessarily synchronous.
	 * Thus, if we want to test that markers are correctly updated we need to be able to
	 * wait for the right time. 
	 */
	public static void waitForMarkerUpdates() {
		MarkerMaker.busy.waitNotBusy();
	}
	
}
