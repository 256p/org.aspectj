/* *******************************************************************
 * Copyright (c) 2002 Palo Alto Research Center, Incorporated (PARC).
 * All rights reserved. 
 * This program and the accompanying materials are made available 
 * under the terms of the Common Public License v1.0 
 * which accompanies this distribution and is available at 
 * http://www.eclipse.org/legal/cpl-v10.html 
 *  
 * Contributors: 
 *     PARC     initial implementation 
 * ******************************************************************/


package org.aspectj.ajdt.internal.core.builder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.aspectj.weaver.bcel.UnwovenClassFile;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFormatException;
import org.eclipse.jdt.internal.core.builder.ReferenceCollection;
import org.eclipse.jdt.internal.core.builder.StringSet;


/**
 * Holds state needed for incremental compilation
 */
public class AjState {
	AjBuildManager buildManager;
	
	long lastSuccessfulBuildTime = -1;
	long currentBuildTime = -1;
	AjBuildConfig buildConfig;
	AjBuildConfig newBuildConfig;
	
	Map/*<File, List<UnwovenClassFile>*/ classesFromFile = new HashMap();
	Map/*<File, ReferenceCollection>*/ references = new HashMap();
	
	Map/*<String, UnwovenClassFile>*/ classesFromName = new HashMap();
	
	ArrayList/*<String>*/ qualifiedStrings;
	ArrayList/*<String>*/ simpleStrings;
	
	Set addedFiles;
	Set deletedFiles;
	
	List addedClassFiles;
	
	public AjState(AjBuildManager buildManager) {
		this.buildManager = buildManager;
	}
	
	void successfulCompile(AjBuildConfig config) {
		buildConfig = config;
		lastSuccessfulBuildTime = currentBuildTime;
	}
	
	/**
	 * Returns false if a batch build is needed.
	 */
	boolean prepareForNextBuild(AjBuildConfig newBuildConfig) {
		currentBuildTime = System.currentTimeMillis();
		
		addedClassFiles = new ArrayList();
		
		if (lastSuccessfulBuildTime == -1 || buildConfig == null) {
			return false;
		}
		
		simpleStrings = new ArrayList();
		qualifiedStrings = new ArrayList();
		
		Set oldFiles = new HashSet(buildConfig.getFiles());
		Set newFiles = new HashSet(newBuildConfig.getFiles());
		
		addedFiles = new HashSet(newFiles);
		addedFiles.removeAll(oldFiles);
		deletedFiles = new HashSet(oldFiles);
		deletedFiles.removeAll(newFiles);
		
		this.newBuildConfig = newBuildConfig;
		
		return true;
	}
	
	private Collection getModifiedFiles() {		
		return getModifiedFiles(lastSuccessfulBuildTime);
	}

	Collection getModifiedFiles(long lastBuildTime) {
		List ret = new ArrayList();
		//not our job to account for new and deleted files
		for (Iterator i = buildConfig.getFiles().iterator(); i.hasNext(); ) {
			File file = (File)i.next();
			if (!file.exists()) continue;
			
			long modTime = file.lastModified();
			//System.out.println("check: " + file + " mod " + modTime + " build " + lastBuildTime);			
			if (modTime >= lastBuildTime) {
				ret.add(file);
			} 
		}
		return ret;
	}


	public List getFilesToCompile(boolean firstPass) {
		List sourceFiles = new ArrayList();
		if (firstPass) {
			Collection modifiedFiles = getModifiedFiles();
			//System.out.println("modified: " + modifiedFiles);
			sourceFiles.addAll(modifiedFiles);
			//??? eclipse IncrementalImageBuilder appears to do this
	//		for (Iterator i = modifiedFiles.iterator(); i.hasNext();) {
	//			File file = (File) i.next();
	//			addDependentsOf(file);
	//		}
			
			sourceFiles.addAll(addedFiles);	
			
			deleteClassFiles();
			
			addAffectedSourceFiles(sourceFiles);
		} else {
			
			addAffectedSourceFiles(sourceFiles);
		}
		return sourceFiles;
	}

	private void deleteClassFiles() {
		for (Iterator i = deletedFiles.iterator(); i.hasNext(); ) {
			File deletedFile = (File)i.next();
			//System.out.println("deleting: " + deletedFile);
			addDependentsOf(deletedFile);
			List unwovenClassFiles = (List)classesFromFile.get(deletedFile);
			classesFromFile.remove(deletedFile);
			//System.out.println("deleting: " + unwovenClassFiles);
			if (unwovenClassFiles == null) continue;
			for (Iterator j = unwovenClassFiles.iterator(); j.hasNext(); ) {
				UnwovenClassFile classFile = (UnwovenClassFile)j.next();
				deleteClassFile(classFile);
			}
		}
	}

	private void deleteClassFile(UnwovenClassFile classFile) {
		classesFromName.remove(classFile.getClassName());
		
		buildManager.bcelWeaver.deleteClassFile(classFile.getClassName());
		try {
			classFile.deleteRealFile();
		} catch (IOException e) {
			//!!! might be okay to ignore
		}
	}

	public void noteClassesFromFile(CompilationResult result, String sourceFileName, List unwovenClassFiles) {
		File sourceFile = new File(sourceFileName);
		
		if (result != null) {
			references.put(sourceFile, new ReferenceCollection(result.qualifiedReferences, result.simpleNameReferences));
		}
		
		List previous = (List)classesFromFile.get(sourceFile);
		List newClassFiles = new ArrayList();
		for (Iterator i = unwovenClassFiles.iterator(); i.hasNext();) {
			UnwovenClassFile cf = (UnwovenClassFile) i.next();
			cf = writeClassFile(cf, findAndRemoveClassFile(cf.getClassName(), previous));
			newClassFiles.add(cf);
			classesFromName.put(cf.getClassName(), cf);
		}
		
		if (previous != null && !previous.isEmpty()) {
			for (Iterator i = previous.iterator(); i.hasNext();) {
				UnwovenClassFile cf = (UnwovenClassFile) i.next();
				deleteClassFile(cf);
			}
		}

		classesFromFile.put(sourceFile, newClassFiles);
	}

	private UnwovenClassFile findAndRemoveClassFile(String name, List previous) {
		if (previous == null) return null;
		for (Iterator i = previous.iterator(); i.hasNext();) {
			UnwovenClassFile cf = (UnwovenClassFile) i.next();
			if (cf.getClassName().equals(name)) {
				i.remove();
				return cf;
			} 
		}
		return null;
	}

	private UnwovenClassFile writeClassFile(UnwovenClassFile cf, UnwovenClassFile previous) {
		if (simpleStrings == null) { // batch build
			addedClassFiles.add(cf);
			return cf;
		}
		
		try {
			if (previous == null) {
				addedClassFiles.add(cf);
				addDependentsOf(cf.getClassName());
				return cf;
			} 
			
			byte[] oldBytes = previous.getBytes();
			byte[] newBytes = cf.getBytes();
			//if (this.compileLoop > 1) { // only optimize files which were recompiled during the dependent pass, see 33990
				notEqual : if (newBytes.length == oldBytes.length) {
					for (int i = newBytes.length; --i >= 0;) {
						if (newBytes[i] != oldBytes[i]) break notEqual;
					}
					//addedClassFiles.add(previous); //!!! performance wasting
					buildManager.bcelWorld.addSourceObjectType(previous.getJavaClass());
					return previous; // bytes are identical so skip them
				}
			//}
			ClassFileReader reader = new ClassFileReader(oldBytes, previous.getFilename().toCharArray());
			// ignore local types since they're only visible inside a single method
			if (!(reader.isLocal() || reader.isAnonymous()) && reader.hasStructuralChanges(newBytes)) {
				addDependentsOf(cf.getClassName());
			}
		} catch (ClassFormatException e) {
			addDependentsOf(cf.getClassName());
		}
		addedClassFiles.add(cf);
		return cf;
	}
	
	private static StringSet makeStringSet(List strings) {
		StringSet ret = new StringSet(strings.size());
		for (Iterator iter = strings.iterator(); iter.hasNext();) {
			String element = (String) iter.next();
			ret.add(element);
		}
		return ret;
	}
		
	
	
	protected void addAffectedSourceFiles(List sourceFiles) {
		if (qualifiedStrings.isEmpty() && simpleStrings.isEmpty()) return;

		// the qualifiedStrings are of the form 'p1/p2' & the simpleStrings are just 'X'
		char[][][] qualifiedNames = ReferenceCollection.internQualifiedNames(makeStringSet(qualifiedStrings));
		// if a well known qualified name was found then we can skip over these
		if (qualifiedNames.length < qualifiedStrings.size())
			qualifiedNames = null;
		char[][] simpleNames = ReferenceCollection.internSimpleNames(makeStringSet(simpleStrings));
		// if a well known name was found then we can skip over these
		if (simpleNames.length < simpleStrings.size())
			simpleNames = null;

		//System.err.println("simple: " + simpleStrings);
		//System.err.println("qualif: " + qualifiedStrings);

		for (Iterator i = references.entrySet().iterator(); i.hasNext();) {
			Map.Entry entry = (Map.Entry) i.next();
			ReferenceCollection refs = (ReferenceCollection)entry.getValue();
			if (refs != null && refs.includes(qualifiedNames, simpleNames)) {
				File file = (File)entry.getKey();
				if (file.exists()) {
					if (!sourceFiles.contains(file)) {  //??? O(n**2)
						sourceFiles.add(file);
					}
				}
			}
		}
		
		qualifiedStrings.clear();
		simpleStrings.clear();
	}

	protected void addDependentsOf(String qualifiedTypeName) {
		int lastDot = qualifiedTypeName.lastIndexOf('.');
		String typeName;
		if (lastDot != -1) {
			String packageName = qualifiedTypeName.substring(0,lastDot).replace('.', '/');
			if (!qualifiedStrings.contains(packageName)) { //??? O(n**2)
				qualifiedStrings.add(packageName);
			}
			typeName = qualifiedTypeName.substring(lastDot+1);
		} else {
			qualifiedStrings.add("");
			typeName = qualifiedTypeName;
		}

			
		int memberIndex = typeName.indexOf('$');
		if (memberIndex > 0)
			typeName = typeName.substring(0, memberIndex);
		if (!simpleStrings.contains(typeName)) {  //??? O(n**2)
			simpleStrings.add(typeName);
		}		
		//System.err.println("adding: " + qualifiedTypeName);
	}

	protected void addDependentsOf(File sourceFile) {
		List l = (List)classesFromFile.get(sourceFile);
		if (l == null) return;
		
		for (Iterator i = l.iterator(); i.hasNext();) {
			UnwovenClassFile cf = (UnwovenClassFile) i.next();
			addDependentsOf(cf.getClassName());
		}
	}
}
