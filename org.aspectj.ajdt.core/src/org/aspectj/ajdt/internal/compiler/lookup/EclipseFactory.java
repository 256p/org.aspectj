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

import java.util.*;

import org.aspectj.ajdt.internal.compiler.ast.AspectDeclaration;
import org.aspectj.ajdt.internal.compiler.ast.AstUtil;
import org.aspectj.ajdt.internal.core.builder.AjBuildManager;
import org.aspectj.ajdt.internal.core.builder.AsmBuilder;
import org.aspectj.bridge.ISourceLocation;
import org.aspectj.bridge.IMessage.Kind;
import org.aspectj.weaver.*;
import org.eclipse.jdt.internal.compiler.ast.*;
import org.eclipse.jdt.internal.compiler.impl.Constant;
import org.eclipse.jdt.internal.compiler.impl.ReferenceContext;
import org.eclipse.jdt.internal.compiler.lookup.*;
import org.eclipse.jdt.internal.compiler.util.CharOperation;

/**
 * 
 * @author Jim Hugunin
 */
public class EclipseFactory {
	public static boolean DEBUG = false;
	
	public AjBuildManager buildManager;
	private LookupEnvironment lookupEnvironment;
	
	private Map/*TypeX, TypeBinding*/ typexToBinding = new HashMap();
	//XXX currently unused
	private Map/*TypeBinding, ResolvedTypeX*/ bindingToResolvedTypeX = new HashMap();
	
	public static EclipseFactory fromLookupEnvironment(LookupEnvironment env) {
		AjLookupEnvironment aenv = (AjLookupEnvironment)env;
		return aenv.factory;
	}
	
	public static EclipseFactory fromScopeLookupEnvironment(Scope scope) {
		return fromLookupEnvironment(AstUtil.getCompilationUnitScope(scope).environment);
	}
	
	
	public EclipseFactory(LookupEnvironment lookupEnvironment) {
		this.lookupEnvironment = lookupEnvironment;
	}
	
	public World getWorld() {
		return buildManager.getWorld();
	}
	
	public void showMessage(
		Kind kind,
		String message,
		ISourceLocation loc1,
		ISourceLocation loc2)
	{
		getWorld().showMessage(kind, message, loc1, loc2);
	}

	public ResolvedTypeX fromEclipse(ReferenceBinding binding) {
		if (binding == null) return ResolvedTypeX.MISSING;
		//??? this seems terribly inefficient
		//System.err.println("resolving: " + binding.getClass() + ", name = " + getName(binding));
		ResolvedTypeX ret = getWorld().resolve(fromBinding(binding));
		//System.err.println("      got: " + ret);
		return ret;
	}	
	
	public ResolvedTypeX[] fromEclipse(ReferenceBinding[] bindings) {
		if (bindings == null) {
			return ResolvedTypeX.NONE;
		}
		int len = bindings.length;
		ResolvedTypeX[] ret = new ResolvedTypeX[len];
		for (int i=0; i < len; i++) {
			ret[i] = fromEclipse(bindings[i]);
		}
		return ret;
	}	
	
	
	private static String getName(TypeBinding binding) {
		String packageName = new String(binding.qualifiedPackageName());
		String className = new String(binding.qualifiedSourceName()).replace('.', '$');
		if (packageName.length() > 0) {
			className = packageName + "." + className;
		}
		//XXX doesn't handle arrays correctly (or primitives?)
		return new String(className);
	}



	//??? going back and forth between strings and bindings is a waste of cycles
	public static TypeX fromBinding(TypeBinding binding) {
		if (binding instanceof HelperInterfaceBinding) {
			return ((HelperInterfaceBinding) binding).getTypeX();
		}
		if (binding.qualifiedSourceName() == null) {
			return ResolvedTypeX.MISSING;
		}
		return TypeX.forName(getName(binding));
	}

	public static TypeX[] fromBindings(TypeBinding[] bindings) {
		int len = bindings.length;
		TypeX[] ret = new TypeX[len];
		for (int i=0; i<len; i++) {
			ret[i] = fromBinding(bindings[i]);
		}
		return ret;
	}

	public static AstNode astForLocation(IHasPosition location) {
		return new EmptyStatement(location.getStart(), location.getEnd());
	}
	
	public Collection getDeclareParents() {
		return getWorld().getDeclareParents();
	}
	
	public Collection finishedTypeMungers = null;
	
	public boolean areTypeMungersFinished() {
		return finishedTypeMungers != null;
	}
	
	public void finishTypeMungers() {
		// make sure that type mungers are
		finishedTypeMungers = new ArrayList();
		Collection baseTypeMungers = 
			getWorld().getCrosscuttingMembersSet().getTypeMungers();
		for (Iterator i = baseTypeMungers.iterator(); i.hasNext(); ) {
			ConcreteTypeMunger munger = (ConcreteTypeMunger) i.next();
			EclipseTypeMunger etm = makeEclipseTypeMunger(munger);
			if (etm != null) finishedTypeMungers.add(etm);
		}		
	}
	
	public EclipseTypeMunger makeEclipseTypeMunger(ConcreteTypeMunger concrete) {
		if (concrete instanceof EclipseTypeMunger) return (EclipseTypeMunger)concrete;
		if (EclipseTypeMunger.supportsKind(concrete.getMunger().getKind())) {
			return new EclipseTypeMunger(this, concrete.getMunger(), concrete.getAspectType(), null);
		} else {
			return null;
		}
	}

	public Collection getTypeMungers() {
		//??? assert finishedTypeMungers != null
		return finishedTypeMungers;
	}
	
	public static ResolvedMember makeResolvedMember(MethodBinding binding) {
		//System.err.println("member for: " + binding + ", " + new String(binding.declaringClass.sourceName));
		ResolvedMember ret =  new ResolvedMember(
			binding.isConstructor() ? Member.CONSTRUCTOR : Member.METHOD,
			fromBinding(binding.declaringClass),
			binding.modifiers,
			fromBinding(binding.returnType),
			new String(binding.selector),
			fromBindings(binding.parameters));
		ret.setCheckedExceptions(fromBindings(binding.thrownExceptions));
		return ret;
	}

	public static ResolvedMember makeResolvedMember(FieldBinding binding) {
		return new ResolvedMember(
			Member.FIELD,
			fromBinding(binding.declaringClass),
			binding.modifiers,
			fromBinding(binding.type),
			new String(binding.name),
			TypeX.NONE);
	}
	
	public TypeBinding makeTypeBinding(TypeX typeX) {
		TypeBinding ret = (TypeBinding)typexToBinding.get(typeX);
		if (ret == null) {
			ret = makeTypeBinding1(typeX);
			typexToBinding.put(typeX, ret);
		}
		return ret;
	}
	
	private TypeBinding makeTypeBinding1(TypeX typeX) {
		if (typeX.isPrimitive()) {
			if (typeX == ResolvedTypeX.BOOLEAN) return BaseTypes.BooleanBinding;
			if (typeX == ResolvedTypeX.BYTE) return BaseTypes.ByteBinding;
			if (typeX == ResolvedTypeX.CHAR) return BaseTypes.CharBinding;
			if (typeX == ResolvedTypeX.DOUBLE) return BaseTypes.DoubleBinding;
			if (typeX == ResolvedTypeX.FLOAT) return BaseTypes.FloatBinding;
			if (typeX == ResolvedTypeX.INT) return BaseTypes.IntBinding;
			if (typeX == ResolvedTypeX.LONG) return BaseTypes.LongBinding;
			if (typeX == ResolvedTypeX.SHORT) return BaseTypes.ShortBinding;
			if (typeX == ResolvedTypeX.VOID) return BaseTypes.VoidBinding;
			throw new RuntimeException("weird primitive type " + typeX);
		} else if (typeX.isArray()) {
			int dim = 0;
			while (typeX.isArray()) {
				dim++;
				typeX = typeX.getComponentType();
			}
			return lookupEnvironment.createArrayType(makeTypeBinding(typeX), dim);
		} else {
			String n = typeX.getName();
			char[][] name = CharOperation.splitOn('.', n.toCharArray());
			return lookupEnvironment.getType(name);
		}
	}
	
	
	
	public TypeBinding[] makeTypeBindings(TypeX[] types) {
		int len = types.length;
		TypeBinding[] ret = new TypeBinding[len];
		
		for (int i = 0; i < len; i++) {
			ret[i] = makeTypeBinding(types[i]);
		}
		return ret;
	}
	
	// just like the code above except it returns an array of ReferenceBindings
	private ReferenceBinding[] makeReferenceBindings(TypeX[] types) {
		int len = types.length;
		ReferenceBinding[] ret = new ReferenceBinding[len];
		
		for (int i = 0; i < len; i++) {
			ret[i] = (ReferenceBinding)makeTypeBinding(types[i]);
		}
		return ret;
	}

	
	public FieldBinding makeFieldBinding(ResolvedMember member) {
		return new FieldBinding(member.getName().toCharArray(),
				makeTypeBinding(member.getReturnType()),
				member.getModifiers(),
				(ReferenceBinding)makeTypeBinding(member.getDeclaringType()),
				Constant.NotAConstant);
	}


	public MethodBinding makeMethodBinding(ResolvedMember member) {
		return new MethodBinding(member.getModifiers(),
				member.getName().toCharArray(),
				makeTypeBinding(member.getReturnType()),
				makeTypeBindings(member.getParameterTypes()),
				makeReferenceBindings(member.getExceptions()),
				(ReferenceBinding)makeTypeBinding(member.getDeclaringType()));
	}


	
	public MethodBinding makeMethodBindingForCall(Member member) {
		return new MethodBinding(member.getCallsiteModifiers(),
				member.getName().toCharArray(),
				makeTypeBinding(member.getReturnType()),
				makeTypeBindings(member.getParameterTypes()),
				new ReferenceBinding[0],
				(ReferenceBinding)makeTypeBinding(member.getDeclaringType()));
	}

	public void finishedCompilationUnit(CompilationUnitDeclaration unit) {
		if (buildManager.doGenerateModel()) {
			AsmBuilder.build(unit, buildManager.getStructureModel());
		}
	}


	public void addTypeBinding(TypeBinding binding) {
		typexToBinding.put(fromBinding(binding), binding);
	}


	public Shadow makeShadow(AstNode location, ReferenceContext context) {
		return EclipseShadow.makeShadow(this, location, context);
	}
	
	public Shadow makeShadow(ReferenceContext context) {
		return EclipseShadow.makeShadow(this, (AstNode) context, context);
	}
	
	public void addSourceTypeBinding(SourceTypeBinding binding) {
		TypeDeclaration decl = binding.scope.referenceContext;
		ResolvedTypeX.Name name = getWorld().lookupOrCreateName(TypeX.forName(getName(binding)));
		EclipseSourceType t = new EclipseSourceType(name, this, binding, decl);
		name.setDelegate(t);
		if (decl instanceof AspectDeclaration) {
			((AspectDeclaration)decl).typeX = name;
			((AspectDeclaration)decl).concreteName = t;
		}
		
		ReferenceBinding[] memberTypes = binding.memberTypes;
		for (int i = 0, length = memberTypes.length; i < length; i++) {
			addSourceTypeBinding((SourceTypeBinding) memberTypes[i]);
		}
	}
}
