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


package org.aspectj.ajdt.internal.compiler.lookup;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.aspectj.ajdt.internal.compiler.ast.AspectDeclaration;
import org.aspectj.ajdt.internal.compiler.ast.PointcutDeclaration;
import org.aspectj.bridge.IMessage;
import org.aspectj.bridge.WeaveMessage;
import org.aspectj.org.eclipse.jdt.core.compiler.CharOperation;
import org.aspectj.org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration;
import org.aspectj.org.eclipse.jdt.internal.compiler.ast.Annotation;
import org.aspectj.org.eclipse.jdt.internal.compiler.ast.QualifiedTypeReference;
import org.aspectj.org.eclipse.jdt.internal.compiler.ast.SingleTypeReference;
import org.aspectj.org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.aspectj.org.eclipse.jdt.internal.compiler.env.AccessRestriction;
import org.aspectj.org.eclipse.jdt.internal.compiler.env.IBinaryType;
import org.aspectj.org.eclipse.jdt.internal.compiler.env.INameEnvironment;
import org.aspectj.org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.aspectj.org.eclipse.jdt.internal.compiler.impl.ITypeRequestor;
import org.aspectj.org.eclipse.jdt.internal.compiler.lookup.BinaryTypeBinding;
import org.aspectj.org.eclipse.jdt.internal.compiler.lookup.ClassScope;
import org.aspectj.org.eclipse.jdt.internal.compiler.lookup.CompilationUnitScope;
import org.aspectj.org.eclipse.jdt.internal.compiler.lookup.LookupEnvironment;
import org.aspectj.org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.aspectj.org.eclipse.jdt.internal.compiler.lookup.PackageBinding;
import org.aspectj.org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding;
import org.aspectj.org.eclipse.jdt.internal.compiler.lookup.SourceTypeBinding;
import org.aspectj.org.eclipse.jdt.internal.compiler.lookup.TagBits;
import org.aspectj.org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.aspectj.org.eclipse.jdt.internal.compiler.problem.ProblemReporter;
import org.aspectj.weaver.AsmRelationshipProvider;
import org.aspectj.weaver.ConcreteTypeMunger;
import org.aspectj.weaver.ReferenceType;
import org.aspectj.weaver.ResolvedTypeMunger;
import org.aspectj.weaver.ResolvedType;
import org.aspectj.weaver.UnresolvedType;
import org.aspectj.weaver.WeaverMessages;
import org.aspectj.weaver.WeaverStateInfo;
import org.aspectj.weaver.World;
import org.aspectj.weaver.bcel.LazyClassGen;
import org.aspectj.weaver.patterns.DeclareAnnotation;
import org.aspectj.weaver.patterns.DeclareParents;

/**
 * Overrides the default eclipse LookupEnvironment for two purposes.
 * 
 * 1. To provide some additional phases to <code>completeTypeBindings</code>
 *    that weave declare parents and inter-type declarations at the correct time.
 * 
 * 2. To intercept the loading of new binary types to ensure the they will have
 *    declare parents and inter-type declarations woven when appropriate.
 * 
 * @author Jim Hugunin
 */
public class AjLookupEnvironment extends LookupEnvironment {
	public  EclipseFactory factory = null;
	
//	private boolean builtInterTypesAndPerClauses = false;
	private List pendingTypesToWeave = new ArrayList();
	private Map dangerousInterfaces = new HashMap();
	
	public AjLookupEnvironment(
		ITypeRequestor typeRequestor,
		CompilerOptions options,
		ProblemReporter problemReporter,
		INameEnvironment nameEnvironment) {
		super(typeRequestor, options, problemReporter, nameEnvironment);
	}
	
	//??? duplicates some of super's code
	public void completeTypeBindings() {
//		builtInterTypesAndPerClauses = false;
		//pendingTypesToWeave = new ArrayList();
		stepCompleted = BUILD_TYPE_HIERARCHY;
		
		for (int i = lastCompletedUnitIndex + 1; i <= lastUnitIndex; i++) {
			units[i].scope.checkAndSetImports();
		}
		stepCompleted = CHECK_AND_SET_IMPORTS;
	
		for (int i = lastCompletedUnitIndex + 1; i <= lastUnitIndex; i++) {
			units[i].scope.connectTypeHierarchy();
		}
		stepCompleted = CONNECT_TYPE_HIERARCHY;
	
		for (int i = lastCompletedUnitIndex + 1; i <= lastUnitIndex; i++) {
			units[i].scope.buildFieldsAndMethods();
		}
		
		// would like to gather up all TypeDeclarations at this point and put them in the factory
		for (int i = lastCompletedUnitIndex + 1; i <= lastUnitIndex; i++) {
			SourceTypeBinding[] b = units[i].scope.topLevelTypes;
			for (int j = 0; j < b.length; j++) {
				factory.addSourceTypeBinding(b[j]);
			}
		}
		
		// need to build inter-type declarations for all AspectDeclarations at this point
        for (int i = lastCompletedUnitIndex + 1; i <= lastUnitIndex; i++) {
            SourceTypeBinding[] b = units[i].scope.topLevelTypes;
            for (int j = 0; j < b.length; j++) {
                buildInterTypeAndPerClause(b[j].scope);
                addCrosscuttingStructures(b[j].scope);
            }
        }        

		factory.finishTypeMungers();
	
		// now do weaving
		Collection typeMungers = factory.getTypeMungers();
		
		Collection declareParents = factory.getDeclareParents();
		Collection declareAnnotationOnTypes = factory.getDeclareAnnotationOnTypes();

		doPendingWeaves();
		
		// We now have some list of types to process, and we are about to apply the type mungers.
		// There can be situations where the order of types passed to the compiler causes the
		// output from the compiler to vary - THIS IS BAD.  For example, if we have class A
		// and class B extends A.  Also, an aspect that 'declare parents: A+ implements Serializable'
		// then depending on whether we see A first, we may or may not make B serializable.
		
		// The fix is to process them in the right order, ensuring that for a type we process its 
		// supertypes and superinterfaces first.  This algorithm may have problems with:
		// - partial hierarchies (e.g. suppose types A,B,C are in a hierarchy and A and C are to be woven but not B)
		// - weaving that brings new types in for processing (see pendingTypesToWeave.add() calls) after we thought
		//   we had the full list.
		// 
		// but these aren't common cases (he bravely said...)
		boolean typeProcessingOrderIsImportant = declareParents.size()>0 || declareAnnotationOnTypes.size()>0; //DECAT
		
		if (typeProcessingOrderIsImportant) {
			List typesToProcess = new ArrayList();
			for (int i=lastCompletedUnitIndex+1; i<=lastUnitIndex; i++) {
				CompilationUnitScope cus = units[i].scope;
				SourceTypeBinding[] stbs = cus.topLevelTypes;
				for (int j=0; j<stbs.length; j++) {
					SourceTypeBinding stb = stbs[j];
					typesToProcess.add(stb);
				}
			}

			while (typesToProcess.size()>0) {
				// A side effect of weaveIntertypes() is that the processed type is removed from the collection
				weaveIntertypes(typesToProcess,(SourceTypeBinding)typesToProcess.get(0),typeMungers,declareParents,declareAnnotationOnTypes);
			}
		
		} else {
			// Order isn't important
			for (int i = lastCompletedUnitIndex + 1; i <= lastUnitIndex; i++) {
				//System.err.println("Working on "+new String(units[i].getFileName()));
				weaveInterTypeDeclarations(units[i].scope, typeMungers, declareParents,declareAnnotationOnTypes);
			}
		}
		
        for (int i = lastCompletedUnitIndex + 1; i <= lastUnitIndex; i++) {
            SourceTypeBinding[] b = units[i].scope.topLevelTypes;
            for (int j = 0; j < b.length; j++) {
                resolvePointcutDeclarations(b[j].scope);
            }
        }
        
        for (int i = lastCompletedUnitIndex + 1; i <= lastUnitIndex; i++) {
            SourceTypeBinding[] b = units[i].scope.topLevelTypes;
            for (int j = 0; j < b.length; j++) {
            	addAdviceLikeDeclares(b[j].scope);
            }
        }
        
        for (int i = lastCompletedUnitIndex + 1; i <= lastUnitIndex; i++) {
            units[i] = null; // release unnecessary reference to the parsed unit
        }
                
		stepCompleted = BUILD_FIELDS_AND_METHODS;
		lastCompletedUnitIndex = lastUnitIndex;
	}
	
	/**
	 * Weave the parents and intertype decls into a given type.  This method looks at the
	 * supertype and superinterfaces for the specified type and recurses to weave those first
	 * if they are in the full list of types we are going to process during this compile... it stops recursing
	 * the first time it hits a type we aren't going to process during this compile.  This could cause problems 
	 * if you supply 'pieces' of a hierarchy, i.e. the bottom and the top, but not the middle - but what the hell
	 * are you doing if you do that?
	 */
	private void weaveIntertypes(List typesToProcess,SourceTypeBinding typeToWeave,Collection typeMungers,
			                     Collection declareParents,Collection declareAnnotationOnTypes) {
		// Look at the supertype first
	    ReferenceBinding superType = typeToWeave.superclass();
	    if (typesToProcess.contains(superType) && superType instanceof SourceTypeBinding) {
	    	//System.err.println("Recursing to supertype "+new String(superType.getFileName()));
	    	weaveIntertypes(typesToProcess,(SourceTypeBinding)superType,typeMungers,declareParents,declareAnnotationOnTypes);
	    }
	    // Then look at the superinterface list
		ReferenceBinding[] interfaceTypes = typeToWeave.superInterfaces();
	    for (int i = 0; i < interfaceTypes.length; i++) {
	    	ReferenceBinding binding = interfaceTypes[i];
	    	if (typesToProcess.contains(binding) && binding instanceof SourceTypeBinding) {
		    	//System.err.println("Recursing to superinterface "+new String(binding.getFileName()));
	    		weaveIntertypes(typesToProcess,(SourceTypeBinding)binding,typeMungers,declareParents,declareAnnotationOnTypes);
	    	}
		}
	    weaveInterTypeDeclarations(typeToWeave,typeMungers,declareParents,declareAnnotationOnTypes,false);
	    typesToProcess.remove(typeToWeave);
	}

	private void doPendingWeaves() {
		for (Iterator i = pendingTypesToWeave.iterator(); i.hasNext(); ) {
			SourceTypeBinding t = (SourceTypeBinding)i.next();
			weaveInterTypeDeclarations(t);
		}
		pendingTypesToWeave.clear();
	}
    
    private void addAdviceLikeDeclares(ClassScope s) {
        TypeDeclaration dec = s.referenceContext;
        
        if (dec instanceof AspectDeclaration) {
            ResolvedType typeX = factory.fromEclipse(dec.binding);
            factory.getWorld().getCrosscuttingMembersSet().addAdviceLikeDeclares(typeX);
        }
        
        SourceTypeBinding sourceType = s.referenceContext.binding;
        ReferenceBinding[] memberTypes = sourceType.memberTypes;
        for (int i = 0, length = memberTypes.length; i < length; i++) {
            addAdviceLikeDeclares(((SourceTypeBinding) memberTypes[i]).scope);
        }
    }

    private void addCrosscuttingStructures(ClassScope s) {
        TypeDeclaration dec = s.referenceContext;
        
        if (dec instanceof AspectDeclaration) {
            ResolvedType typeX = factory.fromEclipse(dec.binding);
            factory.getWorld().getCrosscuttingMembersSet().addOrReplaceAspect(typeX);
        
            if (typeX.getSuperclass().isAspect() && !typeX.getSuperclass().isExposedToWeaver()) {
                factory.getWorld().getCrosscuttingMembersSet().addOrReplaceAspect(typeX.getSuperclass());
            }
        }
        
        SourceTypeBinding sourceType = s.referenceContext.binding;
        ReferenceBinding[] memberTypes = sourceType.memberTypes;
        for (int i = 0, length = memberTypes.length; i < length; i++) {
            addCrosscuttingStructures(((SourceTypeBinding) memberTypes[i]).scope);
        }
    }
    
    private void resolvePointcutDeclarations(ClassScope s) {
        TypeDeclaration dec = s.referenceContext;
        SourceTypeBinding sourceType = s.referenceContext.binding;
        boolean hasPointcuts = false;
        AbstractMethodDeclaration[] methods = dec.methods;
        boolean initializedMethods = false;
        if (methods != null) {
            for (int i=0; i < methods.length; i++) {
                if (methods[i] instanceof PointcutDeclaration) {
                	hasPointcuts = true;
                    if (!initializedMethods) {
                        sourceType.methods(); //force initialization
                        initializedMethods = true;
                    }
                    ((PointcutDeclaration)methods[i]).resolvePointcut(s);
                }
            }
        }

		if (hasPointcuts || dec instanceof AspectDeclaration) {
        	ReferenceType name = (ReferenceType)factory.fromEclipse(sourceType);
        	EclipseSourceType eclipseSourceType = (EclipseSourceType)name.getDelegate();
        	eclipseSourceType.checkPointcutDeclarations();
		}
		
        ReferenceBinding[] memberTypes = sourceType.memberTypes;
        for (int i = 0, length = memberTypes.length; i < length; i++) {
            resolvePointcutDeclarations(((SourceTypeBinding) memberTypes[i]).scope);
        }
    }
    
    

	
	private void buildInterTypeAndPerClause(ClassScope s) {
		TypeDeclaration dec = s.referenceContext;
		if (dec instanceof AspectDeclaration) {
			((AspectDeclaration)dec).buildInterTypeAndPerClause(s);
		}
		
		SourceTypeBinding sourceType = s.referenceContext.binding;
		// test classes don't extend aspects
		if (sourceType.superclass != null) {
			ResolvedType parent = factory.fromEclipse(sourceType.superclass);
			if (parent.isAspect() && !isAspect(dec)) {
				factory.showMessage(IMessage.ERROR, "class \'" + new String(sourceType.sourceName) + 
						"\' can not extend aspect \'" + parent.getName() + "\'",
						factory.fromEclipse(sourceType).getSourceLocation(), null);
			}
		}

		ReferenceBinding[] memberTypes = sourceType.memberTypes;
		for (int i = 0, length = memberTypes.length; i < length; i++) {
			buildInterTypeAndPerClause(((SourceTypeBinding) memberTypes[i]).scope);
		}
	}
	
	private boolean isAspect(TypeDeclaration decl) {
		if ((decl instanceof AspectDeclaration)) {
			return true;
		} else if (decl.annotations == null) {
			return false;
		} else {
			for (int i = 0; i < decl.annotations.length; i++) {
				Annotation ann = decl.annotations[i];
				if (ann.type instanceof SingleTypeReference) {
					if (CharOperation.equals("Aspect".toCharArray(),((SingleTypeReference)ann.type).token)) return true;
				} else if (ann.type instanceof QualifiedTypeReference) {
					QualifiedTypeReference qtr = (QualifiedTypeReference) ann.type;
					if (qtr.tokens.length != 5) return false;
					if (!CharOperation.equals("org".toCharArray(),qtr.tokens[0])) return false;
					if (!CharOperation.equals("aspectj".toCharArray(),qtr.tokens[1])) return false;
					if (!CharOperation.equals("lang".toCharArray(),qtr.tokens[2])) return false;
					if (!CharOperation.equals("annotation".toCharArray(),qtr.tokens[3])) return false;
					if (!CharOperation.equals("Aspect".toCharArray(),qtr.tokens[4])) return false;
					return true;
				}
			}
		}
		return false;		
	}
		
	private void weaveInterTypeDeclarations(CompilationUnitScope unit, Collection typeMungers, 
			                                Collection declareParents, Collection declareAnnotationOnTypes) {
		for (int i = 0, length = unit.topLevelTypes.length; i < length; i++) {
		    weaveInterTypeDeclarations(unit.topLevelTypes[i], typeMungers, declareParents, declareAnnotationOnTypes,false);
		}
	}
	
	private void weaveInterTypeDeclarations(SourceTypeBinding sourceType) {
		if (!factory.areTypeMungersFinished()) {
			if (!pendingTypesToWeave.contains(sourceType)) pendingTypesToWeave.add(sourceType);
		} else {
			weaveInterTypeDeclarations(sourceType, factory.getTypeMungers(), factory.getDeclareParents(), factory.getDeclareAnnotationOnTypes(),true);
		}
	}
	
	private void weaveInterTypeDeclarations(SourceTypeBinding sourceType, Collection typeMungers, 
			Collection declareParents, Collection declareAnnotationOnTypes, boolean skipInners) {
		ResolvedType onType = factory.fromEclipse(sourceType);
		// AMC we shouldn't need this when generic sigs are fixed??
		if (onType.isRawType()) onType = onType.getGenericType();
		WeaverStateInfo info = onType.getWeaverState();

		if (info != null && !info.isOldStyle()) {		
			Collection mungers = 
				onType.getWeaverState().getTypeMungers(onType);
				
			//System.out.println(onType + " mungers: " + mungers);
			for (Iterator i = mungers.iterator(); i.hasNext(); ) {
				ConcreteTypeMunger m = (ConcreteTypeMunger)i.next();
				EclipseTypeMunger munger = factory.makeEclipseTypeMunger(m);
				if (munger.munge(sourceType)) {
					if (onType.isInterface() &&
						munger.getMunger().needsAccessToTopmostImplementor())
					{
						if (!onType.getWorld().getCrosscuttingMembersSet().containsAspect(munger.getAspectType())) {
							dangerousInterfaces.put(onType, 
								"implementors of " + onType + " must be woven by " +
								munger.getAspectType());
						}
					}
				}
				
			}
			
			return;
		}
		
		//System.out.println("dangerousInterfaces: " + dangerousInterfaces);
		
		for (Iterator i = dangerousInterfaces.entrySet().iterator(); i.hasNext();) {
			Map.Entry entry = (Map.Entry) i.next();
			ResolvedType interfaceType = (ResolvedType)entry.getKey();
			if (onType.isTopmostImplementor(interfaceType)) {
				factory.showMessage(IMessage.ERROR, 
					onType + ": " + entry.getValue(),
					onType.getSourceLocation(), null);
			}
		}
		
		boolean needOldStyleWarning = (info != null && info.isOldStyle());
		
		onType.clearInterTypeMungers();
		
		// FIXME asc perf Could optimize here, after processing the expected set of types we may bring
		// binary types that are not exposed to the weaver, there is no need to attempt declare parents
		// or declare annotation really - unless we want to report the not-exposed to weaver
		// messages...
		
		List decpToRepeat = new ArrayList();
		List decaToRepeat = new ArrayList();
		boolean anyNewParents = false;
		boolean anyNewAnnotations = false;

		// first pass 
		// try and apply all decps - if they match, then great.  If they don't then
		// check if they are starred-annotation patterns.  If they are not starred
		// annotation patterns then they might match later...remember that...
		for (Iterator i = declareParents.iterator(); i.hasNext();) {
			DeclareParents decp = (DeclareParents)i.next();
			boolean didSomething = doDeclareParents(decp, sourceType);
			if (didSomething) {
				anyNewParents = true;
			} else {
				if (!decp.getChild().isStarAnnotation()) decpToRepeat.add(decp);
			}
		}

		for (Iterator i = declareAnnotationOnTypes.iterator(); i.hasNext();) {
			DeclareAnnotation deca = (DeclareAnnotation)i.next();
			boolean didSomething = doDeclareAnnotations(deca, sourceType,true);
			if (didSomething) {
				anyNewAnnotations = true;
			} else {
				if (!deca.getTypePattern().isStar()) decaToRepeat.add(deca);
			}
		}
		
        // now lets loop over and over until we have done all we can
		while ((anyNewAnnotations || anyNewParents) && 
				(!decpToRepeat.isEmpty() || !decaToRepeat.isEmpty())) {
			anyNewParents = anyNewAnnotations = false;
			List forRemoval = new ArrayList();
			for (Iterator i = decpToRepeat.iterator(); i.hasNext();) {
				DeclareParents decp = (DeclareParents)i.next();
				boolean didSomething = doDeclareParents(decp, sourceType);
				if (didSomething) {
					anyNewParents = true;
					forRemoval.add(decp);
				}
			}
			decpToRepeat.removeAll(forRemoval);

            forRemoval = new ArrayList();
			for (Iterator i = declareAnnotationOnTypes.iterator(); i.hasNext();) {
				DeclareAnnotation deca = (DeclareAnnotation)i.next();
				boolean didSomething = doDeclareAnnotations(deca, sourceType,false);
				if (didSomething) {
					anyNewAnnotations = true;
					forRemoval.add(deca);
				} 
			}
			decaToRepeat.removeAll(forRemoval);
		}
		
		
		for (Iterator i = typeMungers.iterator(); i.hasNext();) {
			EclipseTypeMunger munger = (EclipseTypeMunger) i.next();
			if (munger.matches(onType)) {
				if (needOldStyleWarning) {
					factory.showMessage(IMessage.WARNING, 
						"The class for " + onType + " should be recompiled with ajc-1.1.1 for best results",
						onType.getSourceLocation(), null);
					needOldStyleWarning = false;
				}
				onType.addInterTypeMunger(munger);
				//TODO: Andy Should be done at weave time.
				// Unfortunately we can't do it at weave time unless the type mungers remember where
				// they came from.  Thats why we do it here during complation because at this time
				// they do know their source location.  I've put a flag in ResolvedTypeMunger that
				// records whether type mungers are currently set to remember their source location.
				// The flag is currently set to false, it should be set to true when we do the
				// work to version all AspectJ attributes.
				// (When done at weave time, it is done by invoking addRelationship() on 
				// AsmRelationshipProvider (see BCELTypeMunger)
				if (!ResolvedTypeMunger.persistSourceLocation) // Do it up front if we bloody have to
				 AsmInterTypeRelationshipProvider.getDefault().addRelationship(onType, munger);
			}
		}
		
		
        //???onType.checkInterTypeMungers();
        onType.checkInterTypeMungers();
		for (Iterator i = onType.getInterTypeMungers().iterator(); i.hasNext();) {
			EclipseTypeMunger munger = (EclipseTypeMunger) i.next();
			//System.out.println("applying: " + munger + " to " + new String(sourceType.sourceName));
			munger.munge(sourceType);
		}
		
		// Call if you would like to do source weaving of declare @method/@constructor 
		// at source time... no need to do this as it can't impact anything, but left here for
		// future generations to enjoy.  Method source is commented out at the end of this module
		// doDeclareAnnotationOnMethods();
     
		// Call if you would like to do source weaving of declare @field 
		// at source time... no need to do this as it can't impact anything, but left here for
		// future generations to enjoy.  Method source is commented out at the end of this module
		// doDeclareAnnotationOnFields();

		if (skipInners) return;

		ReferenceBinding[] memberTypes = sourceType.memberTypes;
		for (int i = 0, length = memberTypes.length; i < length; i++) {
			if (memberTypes[i] instanceof SourceTypeBinding) {
				weaveInterTypeDeclarations((SourceTypeBinding) memberTypes[i], typeMungers, declareParents,declareAnnotationOnTypes, false);
			}
		}
	}
	
	private boolean doDeclareParents(DeclareParents declareParents, SourceTypeBinding sourceType) {
		List newParents = declareParents.findMatchingNewParents(factory.fromEclipse(sourceType),false);
		if (!newParents.isEmpty()) {
			for (Iterator i = newParents.iterator(); i.hasNext(); ) {
				ResolvedType parent = (ResolvedType)i.next();
				if (dangerousInterfaces.containsKey(parent)) {
					ResolvedType onType = factory.fromEclipse(sourceType);
					factory.showMessage(IMessage.ERROR, 
										onType + ": " + dangerousInterfaces.get(parent),
										onType.getSourceLocation(), null);
				}
				if (Modifier.isFinal(parent.getModifiers())) {
					factory.showMessage(IMessage.ERROR,"cannot extend final class " + parent.getClassName(),declareParents.getSourceLocation(),null);
				} else {
					AsmRelationshipProvider.getDefault().addDeclareParentsRelationship(declareParents.getSourceLocation(),factory.fromEclipse(sourceType), newParents);
					addParent(sourceType, parent);
				}
			}
			return true;
		}
		return false;
	}
	
	private String stringifyTargets(long bits) {
		if ((bits & TagBits.AnnotationTargetMASK)==0) return "";
		Set s = new HashSet();
		if ((bits&TagBits.AnnotationForAnnotationType)!=0) s.add("ANNOTATION_TYPE");
		if ((bits&TagBits.AnnotationForConstructor)!=0) s.add("CONSTRUCTOR");
		if ((bits&TagBits.AnnotationForField)!=0) s.add("FIELD");
		if ((bits&TagBits.AnnotationForLocalVariable)!=0) s.add("LOCAL_VARIABLE");
		if ((bits&TagBits.AnnotationForMethod)!=0) s.add("METHOD");
		if ((bits&TagBits.AnnotationForPackage)!=0) s.add("PACKAGE");
		if ((bits&TagBits.AnnotationForParameter)!=0) s.add("PARAMETER");
		if ((bits&TagBits.AnnotationForType)!=0) s.add("TYPE");
		StringBuffer sb = new StringBuffer();
		sb.append("{");
		for (Iterator iter = s.iterator(); iter.hasNext();) {
			String element = (String) iter.next();
			sb.append(element);
			if (iter.hasNext()) sb.append(",");
		}
		sb.append("}");
		return sb.toString();
	}
	
	private boolean doDeclareAnnotations(DeclareAnnotation decA, SourceTypeBinding sourceType,boolean reportProblems) {
		ResolvedType rtx = factory.fromEclipse(sourceType);
		if (!decA.matches(rtx)) return false;
		if (!rtx.isExposedToWeaver()) return false;

		
		// Get the annotation specified in the declare
		TypeBinding tb = factory.makeTypeBinding(decA.getAspect());
		
		SourceTypeBinding stb = null;
		// TODO asc determine if there really is a problem here (see comment below)
		
		// ClassCastException here means we probably have either a parameterized type or a raw type, we need the
		// commented out code to get it to work ... currently uncommented because I've not seen a case where its
		// required yet ...
		stb = (SourceTypeBinding)tb;
		MethodBinding[] mbs = stb.getMethods(decA.getAnnotationMethod().toCharArray());
		long abits = mbs[0].getAnnotationTagBits(); // ensure resolved
		TypeDeclaration typeDecl = ((SourceTypeBinding)mbs[0].declaringClass).scope.referenceContext;
		AbstractMethodDeclaration methodDecl = typeDecl.declarationOf(mbs[0]);
		Annotation[] toAdd = methodDecl.annotations; // this is what to add
		abits = toAdd[0].resolvedType.getAnnotationTagBits();
		
		Annotation currentAnnotations[] = sourceType.scope.referenceContext.annotations;
		if (currentAnnotations!=null) 
		for (int i = 0; i < currentAnnotations.length; i++) {
			Annotation annotation = currentAnnotations[i];
			String a = CharOperation.toString(annotation.type.getTypeName());
			String b = CharOperation.toString(toAdd[0].type.getTypeName());
			// FIXME asc we have a lint for attempting to add an annotation twice to a method,
			// we could put it out here *if* we can resolve the problem of errors coming out
			// multiple times if we have cause to loop through here
			if (a.equals(b)) return false;
		}
		
		if (((abits & TagBits.AnnotationTargetMASK)!=0)) {
			if ( (abits & (TagBits.AnnotationForAnnotationType | TagBits.AnnotationForType))==0) {
				// this means it specifies something other than annotation or normal type - error will have been already reported, just resolution process above
				return false;
			}
			if (  (sourceType.isAnnotationType() && (abits & TagBits.AnnotationForAnnotationType)==0) ||
			      (!sourceType.isAnnotationType() && (abits & TagBits.AnnotationForType)==0) ) {
			
			if (reportProblems) {
			  if (decA.isExactPattern()) {
			    factory.showMessage(IMessage.ERROR,
					WeaverMessages.format(WeaverMessages.INCORRECT_TARGET_FOR_DECLARE_ANNOTATION,rtx.getName(),toAdd[0].type,stringifyTargets(abits)),
					decA.getSourceLocation(), null);
			  } 
			  // dont put out the lint - the weaving process will do that
//			  else {
//				if (factory.getWorld().getLint().invalidTargetForAnnotation.isEnabled()) {
//					factory.getWorld().getLint().invalidTargetForAnnotation.signal(new String[]{rtx.getName(),toAdd[0].type.toString(),stringifyTargets(abits)},decA.getSourceLocation(),null);
//				}
//			  }
			}
			return false;
		  }
		}
		
		// Build a new array of annotations
		// FIXME asc Should be caching the old set of annotations in the type so the class file
		// generated doesn't have the annotation, it will then be added again during
		// binary weaving? (similar to declare parents handling...)
		AsmRelationshipProvider.getDefault().addDeclareAnnotationRelationship(decA.getSourceLocation(),rtx.getSourceLocation());
		Annotation abefore[] = sourceType.scope.referenceContext.annotations;
		Annotation[] newset = new Annotation[toAdd.length+(abefore==null?0:abefore.length)];
		System.arraycopy(toAdd,0,newset,0,toAdd.length);
		if (abefore!=null) {
			System.arraycopy(abefore,0,newset,toAdd.length,abefore.length);
		}
		sourceType.scope.referenceContext.annotations = newset;
		return true;
	}
	

	private void reportDeclareParentsMessage(WeaveMessage.WeaveMessageKind wmk,SourceTypeBinding sourceType,ResolvedType parent) {
		if (!factory.getWorld().getMessageHandler().isIgnoring(IMessage.WEAVEINFO)) {
			String filename = new String(sourceType.getFileName());
			
			int takefrom = filename.lastIndexOf('/');
			if (takefrom == -1 ) takefrom = filename.lastIndexOf('\\');
			filename = filename.substring(takefrom+1);

			factory.getWorld().getMessageHandler().handleMessage(
			WeaveMessage.constructWeavingMessage(wmk,
				new String[]{CharOperation.toString(sourceType.compoundName),
						filename,
						parent.getClassName(),
						getShortname(parent.getSourceLocation().getSourceFile().getPath())}));
		}
	}
	
	private String getShortname(String path)  {
		int takefrom = path.lastIndexOf('/');
		if (takefrom == -1) {
			takefrom = path.lastIndexOf('\\');
		}
		return path.substring(takefrom+1);
	}

	private void addParent(SourceTypeBinding sourceType, ResolvedType parent) {
		ReferenceBinding parentBinding = (ReferenceBinding)factory.makeTypeBinding(parent); 
		
        sourceType.rememberTypeHierarchy();
		if (parentBinding.isClass()) {
			sourceType.superclass = parentBinding;
			
            // this used to be true, but I think I've fixed it now, decp is done at weave time!			
			// TAG: WeavingMessage    DECLARE PARENTS: EXTENDS
			// Compiler restriction: Can't do EXTENDS at weave time
			// So, only see this message if doing a source compilation
		    // reportDeclareParentsMessage(WeaveMessage.WEAVEMESSAGE_DECLAREPARENTSEXTENDS,sourceType,parent);
			
		} else {
			ReferenceBinding[] oldI = sourceType.superInterfaces;
			ReferenceBinding[] newI;
			if (oldI == null) {
				newI = new ReferenceBinding[1];
				newI[0] = parentBinding;
			} else {
				int n = oldI.length;
				newI = new ReferenceBinding[n+1];
				System.arraycopy(oldI, 0, newI, 0, n);
				newI[n] = parentBinding;
			}
			sourceType.superInterfaces = newI;
			// warnOnAddedInterface(factory.fromEclipse(sourceType),parent); // now reported at weave time...
			

            // this used to be true, but I think I've fixed it now, decp is done at weave time!			
			// TAG: WeavingMessage    DECLARE PARENTS: IMPLEMENTS
			// This message will come out of BcelTypeMunger.munge if doing a binary weave
	        // reportDeclareParentsMessage(WeaveMessage.WEAVEMESSAGE_DECLAREPARENTSIMPLEMENTS,sourceType,parent);
			
		}
		
	}

	public void warnOnAddedInterface (ResolvedType type, ResolvedType parent) {
		World world = factory.getWorld();
		ResolvedType serializable = world.getCoreType(UnresolvedType.SERIALIZABLE);
		if (serializable.isAssignableFrom(type)
			&& !serializable.isAssignableFrom(parent)
			&& !LazyClassGen.hasSerialVersionUIDField(type)) {
			world.getLint().needsSerialVersionUIDField.signal(
				new String[] {
					type.getName().toString(),
					"added interface " + parent.getName().toString()
				},
				null,
				null);               
		}
	}
	
	
	
	private List pendingTypesToFinish = new ArrayList();
	boolean inBinaryTypeCreationAndWeaving = false;
	boolean processingTheQueue = false;
	
	public BinaryTypeBinding createBinaryTypeFrom(
		IBinaryType binaryType,
		PackageBinding packageBinding,
		boolean needFieldsAndMethods,
		AccessRestriction accessRestriction)
	{

		if (inBinaryTypeCreationAndWeaving) {
			BinaryTypeBinding ret = super.createBinaryTypeFrom(
				binaryType,
				packageBinding,
				needFieldsAndMethods,
				accessRestriction);
			pendingTypesToFinish.add(ret);
			return ret;
		}
		
		inBinaryTypeCreationAndWeaving = true;
		try {
			BinaryTypeBinding ret = super.createBinaryTypeFrom(
				binaryType,
				packageBinding,
				needFieldsAndMethods,
				accessRestriction);
			weaveInterTypeDeclarations(ret);			
			return ret;
		} finally {
			inBinaryTypeCreationAndWeaving = false;
			
			// Start processing the list...
			if (pendingTypesToFinish.size()>0) {
				processingTheQueue = true;
				while (!pendingTypesToFinish.isEmpty()) {
					BinaryTypeBinding nextVictim = (BinaryTypeBinding)pendingTypesToFinish.remove(0);
					// During this call we may recurse into this method and add 
					// more entries to the pendingTypesToFinish list.
					weaveInterTypeDeclarations(nextVictim);
				}
				processingTheQueue = false;
			}
		}		
	}
}

// commented out, supplied as info on how to manipulate annotations in an eclipse world
//
// public void doDeclareAnnotationOnMethods() {
// Do the declare annotation on fields/methods/ctors
//Collection daoms = factory.getDeclareAnnotationOnMethods();
//if (daoms!=null && daoms.size()>0 && !(sourceType instanceof BinaryTypeBinding)) {
//	System.err.println("Going through the methods on "+sourceType.debugName()+" looking for DECA matches");
//	// We better take a look through them...
//	for (Iterator iter = daoms.iterator(); iter.hasNext();) {
//		DeclareAnnotation element = (DeclareAnnotation) iter.next();
//		System.err.println("Looking for anything that might match "+element+" on "+sourceType.debugName()+"  "+getType(sourceType.compoundName).debugName()+"  "+(sourceType instanceof BinaryTypeBinding));
//		
//		ReferenceBinding rbb = getType(sourceType.compoundName);
//		// fix me if we ever uncomment this code... should iterate the other way round, over the methods then over the decas
//		sourceType.methods();
//		MethodBinding sourceMbs[] = sourceType.methods;
//		for (int i = 0; i < sourceMbs.length; i++) {
//			MethodBinding sourceMb = sourceMbs[i];
//			MethodBinding mbbbb = ((SourceTypeBinding)rbb).getExactMethod(sourceMb.selector,sourceMb.parameters);
//			boolean isCtor = sourceMb.selector[0]=='<';
//			
//			if ((element.isDeclareAtConstuctor() ^ !isCtor)) {
//			System.err.println("Checking "+sourceMb+" ... declaringclass="+sourceMb.declaringClass.debugName()+" rbb="+rbb.debugName()+"  "+sourceMb.declaringClass.equals(rbb));
//			
//			ResolvedMember rm = null;
//			rm = EclipseFactory.makeResolvedMember(mbbbb);
//			if (element.matches(rm,factory.getWorld())) {
//				System.err.println("MATCH");
//				
//				// Determine the set of annotations that are currently on the method
//				ReferenceBinding rb = getType(sourceType.compoundName);
////				TypeBinding tb = factory.makeTypeBinding(decA.getAspect());
//				MethodBinding mb = ((SourceTypeBinding)rb).getExactMethod(sourceMb.selector,sourceMb.parameters);
//				//long abits = mbs[0].getAnnotationTagBits(); // ensure resolved
//				TypeDeclaration typeDecl = ((SourceTypeBinding)sourceMb.declaringClass).scope.referenceContext;
//				AbstractMethodDeclaration methodDecl = typeDecl.declarationOf(sourceMb);
//				Annotation[] currentlyHas = methodDecl.annotations; // this is what to add
//				//abits = toAdd[0].resolvedType.getAnnotationTagBits();
//				
//				// Determine the annotations to add to that method
//				TypeBinding tb = factory.makeTypeBinding(element.getAspect());
//				MethodBinding[] aspectMbs = ((SourceTypeBinding)tb).getMethods(element.getAnnotationMethod().toCharArray());
//				long abits = aspectMbs[0].getAnnotationTagBits(); // ensure resolved
//				TypeDeclaration typeDecl2 = ((SourceTypeBinding)aspectMbs[0].declaringClass).scope.referenceContext;
//				AbstractMethodDeclaration methodDecl2 = typeDecl2.declarationOf(aspectMbs[0]);
//				Annotation[] toAdd = methodDecl2.annotations; // this is what to add
//				// abits = toAdd[0].resolvedType.getAnnotationTagBits();
//System.err.println("Has: "+currentlyHas+"    toAdd: "+toAdd);
//				
//				// fix me? should check if it already has the annotation
//				//Annotation abefore[] = sourceType.scope.referenceContext.annotations;
//				Annotation[] newset = new Annotation[(currentlyHas==null?0:currentlyHas.length)+1];
//				System.arraycopy(toAdd,0,newset,0,toAdd.length);
//				if (currentlyHas!=null) {
//					System.arraycopy(currentlyHas,0,newset,1,currentlyHas.length);
//				}
//				methodDecl.annotations = newset;
//				System.err.println("New set on "+CharOperation.charToString(sourceMb.selector)+" is "+newset);
//			} else
//				System.err.println("NO MATCH");
//		}
//	}
//	}
//}
//}

// commented out, supplied as info on how to manipulate annotations in an eclipse world
//
// public void doDeclareAnnotationOnFields() {
//		Collection daofs = factory.getDeclareAnnotationOnFields();
//		if (daofs!=null && daofs.size()>0 && !(sourceType instanceof BinaryTypeBinding)) {
//			System.err.println("Going through the fields on "+sourceType.debugName()+" looking for DECA matches");
//			// We better take a look through them...
//			for (Iterator iter = daofs.iterator(); iter.hasNext();) {
//				DeclareAnnotation element = (DeclareAnnotation) iter.next();
//				System.err.println("Processing deca "+element+" on "+sourceType.debugName()+"  "+getType(sourceType.compoundName).debugName()+"  "+(sourceType instanceof BinaryTypeBinding));
//				
//				ReferenceBinding rbb = getType(sourceType.compoundName);
//				// fix me? should iterate the other way round, over the methods then over the decas
//				sourceType.fields(); // resolve the bloody things
//				FieldBinding sourceFbs[] = sourceType.fields;
//				for (int i = 0; i < sourceFbs.length; i++) {
//					FieldBinding sourceFb = sourceFbs[i];
//					//FieldBinding fbbbb = ((SourceTypeBinding)rbb).getgetExactMethod(sourceMb.selector,sourceMb.parameters);
//					
//					System.err.println("Checking "+sourceFb+" ... declaringclass="+sourceFb.declaringClass.debugName()+" rbb="+rbb.debugName());
//					
//					ResolvedMember rm = null;
//					rm = EclipseFactory.makeResolvedMember(sourceFb);
//					if (element.matches(rm,factory.getWorld())) {
//						System.err.println("MATCH");
//						
//						// Determine the set of annotations that are currently on the field
//						ReferenceBinding rb = getType(sourceType.compoundName);
////						TypeBinding tb = factory.makeTypeBinding(decA.getAspect());
//						FieldBinding fb = ((SourceTypeBinding)rb).getField(sourceFb.name,true);
//						//long abits = mbs[0].getAnnotationTagBits(); // ensure resolved
//						TypeDeclaration typeDecl = ((SourceTypeBinding)sourceFb.declaringClass).scope.referenceContext;
//						FieldDeclaration fd = typeDecl.declarationOf(sourceFb);
//						//AbstractMethodDeclaration methodDecl = typeDecl.declarationOf(sourceMb);
//						Annotation[] currentlyHas = fd.annotations; // this is what to add
//						//abits = toAdd[0].resolvedType.getAnnotationTagBits();
//						
//						// Determine the annotations to add to that method
//						TypeBinding tb = factory.makeTypeBinding(element.getAspect());
//						MethodBinding[] aspectMbs = ((SourceTypeBinding)tb).getMethods(element.getAnnotationMethod().toCharArray());
//						long abits = aspectMbs[0].getAnnotationTagBits(); // ensure resolved
//						TypeDeclaration typeDecl2 = ((SourceTypeBinding)aspectMbs[0].declaringClass).scope.referenceContext;
//						AbstractMethodDeclaration methodDecl2 = typeDecl2.declarationOf(aspectMbs[0]);
//						Annotation[] toAdd = methodDecl2.annotations; // this is what to add
//						// abits = toAdd[0].resolvedType.getAnnotationTagBits();
//System.err.println("Has: "+currentlyHas+"    toAdd: "+toAdd);
//						
//						// fix me? check if it already has the annotation
//
//
//						//Annotation abefore[] = sourceType.scope.referenceContext.annotations;
//						Annotation[] newset = new Annotation[(currentlyHas==null?0:currentlyHas.length)+1];
//						System.arraycopy(toAdd,0,newset,0,toAdd.length);
//						if (currentlyHas!=null) {
//							System.arraycopy(currentlyHas,0,newset,1,currentlyHas.length);
//						}
//						fd.annotations = newset;
//						System.err.println("New set on "+CharOperation.charToString(sourceFb.name)+" is "+newset);
//					} else
//						System.err.println("NO MATCH");
//				}
//			
//			}
//		}
