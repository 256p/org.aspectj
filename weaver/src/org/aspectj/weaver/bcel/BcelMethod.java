/* *******************************************************************
 * Copyright (c) 2002 Palo Alto Research Center, Incorporated (PARC).
 * All rights reserved. 
 * This program and the accompanying materials are made available 
 * under the terms of the Eclipse Public License v1.0 
 * which accompanies this distribution and is available at 
 * http://www.eclipse.org/legal/epl-v10.html 
 *  
 * Contributors: 
 *     PARC     initial implementation 
 * ******************************************************************/


package org.aspectj.weaver.bcel;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import org.aspectj.apache.bcel.classfile.Attribute;
import org.aspectj.apache.bcel.classfile.ExceptionTable;
import org.aspectj.apache.bcel.classfile.GenericSignatureParser;
import org.aspectj.apache.bcel.classfile.JavaClass;
import org.aspectj.apache.bcel.classfile.LineNumber;
import org.aspectj.apache.bcel.classfile.LineNumberTable;
import org.aspectj.apache.bcel.classfile.LocalVariable;
import org.aspectj.apache.bcel.classfile.LocalVariableTable;
import org.aspectj.apache.bcel.classfile.Method;
import org.aspectj.apache.bcel.classfile.Signature;
import org.aspectj.apache.bcel.classfile.Signature.TypeVariableSignature;
import org.aspectj.apache.bcel.classfile.annotation.AnnotationGen;
import org.aspectj.bridge.ISourceLocation;
import org.aspectj.bridge.SourceLocation;
import org.aspectj.weaver.AjAttribute;
import org.aspectj.weaver.AnnotationX;
import org.aspectj.weaver.BCException;
import org.aspectj.weaver.ISourceContext;
import org.aspectj.weaver.Member;
import org.aspectj.weaver.ResolvedMemberImpl;
import org.aspectj.weaver.ResolvedPointcutDefinition;
import org.aspectj.weaver.ResolvedType;
import org.aspectj.weaver.ShadowMunger;
import org.aspectj.weaver.TypeVariable;
import org.aspectj.weaver.UnresolvedType;
import org.aspectj.weaver.World;
import org.aspectj.weaver.bcel.BcelGenericSignatureToTypeXConverter.GenericSignatureFormatException;
import java.util.*;

public final class BcelMethod extends ResolvedMemberImpl {

	private Method method;
//	private AjAttribute.MethodDeclarationLineNumberAttribute declarationLineNumber;
//	private World world;
	private BcelObjectType bcelObjectType;

	public Member slimline() {
		if (!bcelObjectType.getWorld().isXnoInline()) return this;
		ResolvedMemberImpl mi = new ResolvedMemberImpl(kind,declaringType,modifiers,returnType,name,parameterTypes);
		mi.setParameterNames(getParameterNames());
		return mi;
	}

//    private AnnotationX[] annotations = null;
//	private UnresolvedType genericReturnType = null;
//	private UnresolvedType[] genericParameterTypes = null;
	
	private Map metaData = null;
	// keys into the meta data
	private static final String MAPKEY_EFFECTIVE_SIGNATURE     ="effectiveSignature";
	private static final String MAPKEY_PRERESOLVED_POINTCUT    ="preresolvedPointcut";
	private static final String MAPKEY_ASSOCIATED_SHADOWMUNGER ="associatedShadowmunger";
	private static final String MAPKEY_GENERIC_RETURN_TYPE     ="genericReturnType";
	private static final String MAPKEY_GENERIC_PARAM_TYPES     ="genericParameterTypes";
	private static final String MAPKEY_ANNOTATIONS             ="annotations";
	private static final String MAPKEY_MD_LINE_NUMBER_ATTRIBUTE="mdLineNumberAttribute";

//	private AjAttribute.EffectiveSignatureAttribute effectiveSignature;
//	private ShadowMunger associatedShadowMunger;
//	private ResolvedPointcutDefinition preResolvedPointcut;  // used when ajc has pre-resolved the pointcut of some @Advice	

	private int bitflags;
	private static final int KNOW_IF_SYNTHETIC           = 0x0001;
	private static final int PARAMETER_NAMES_INITIALIZED = 0x0002;
	private static final int CAN_BE_PARAMETERIZED        = 0x0004;
	private static final int UNPACKED_GENERIC_SIGNATURE  = 0x0008;
	private static final int HAS_EFFECTIVE_SIGNATURE     = 0x0010;
	private static final int HAS_PRERESOLVED_POINTCUT    = 0x0020;
	private static final int IS_AJ_SYNTHETIC             = 0x0040;
	private static final int IS_SYNTHETIC                = 0x0080;
	private static final int IS_SYNTHETIC_INVERSE        = 0x7f7f; // all bits but IS_SYNTHETIC (and topmost bit)
	private static final int HAS_ASSOCIATED_SHADOWMUNGER = 0x0100;
	private static final int HAS_GENERIC_RETPARAM_TYPES  = 0x0200;
	private static final int HAS_ANNOTATIONS             = 0x0400;
	private static final int HAVE_DETERMINED_ANNOTATIONS = 0x0800;
	private static final int HAS_MD_LINE_NUMBER_ATTRIBUTE= 0x1000;
	
//	private boolean isAjSynthetic;
//	private boolean isSynthetic;
//	private boolean knowIfSynthetic = false;
//	private boolean parameterNamesInitialized = false;
//  private boolean canBeParameterized = false; 
//	private boolean unpackedGenericSignature = false;

	BcelMethod(BcelObjectType declaringType, Method method) {
		super(
			method.getName().equals("<init>") ? CONSTRUCTOR : 
				(method.getName().equals("<clinit>") ? STATIC_INITIALIZATION : METHOD), 
			declaringType.getResolvedTypeX(),
			declaringType.isInterface() 
				? method.getAccessFlags() | Modifier.INTERFACE
				: method.getAccessFlags(),
			method.getName(), 
			method.getSignature());
		this.method = method;
		this.sourceContext = declaringType.getResolvedTypeX().getSourceContext();
		//this.world = declaringType.getResolvedTypeX().getWorld();
		this.bcelObjectType = declaringType;
		unpackJavaAttributes();
		unpackAjAttributes(bcelObjectType.getWorld());
	}

	// ----

	private void unpackJavaAttributes() {
		ExceptionTable exnTable = method.getExceptionTable();
		checkedExceptions = (exnTable == null) 
			? UnresolvedType.NONE
			: UnresolvedType.forNames(exnTable.getExceptionNames());
			
	}
	
	public String[] getParameterNames() {
		determineParameterNames();
		return super.getParameterNames();
	}

    public int getLineNumberOfFirstInstruction() {
    	LineNumberTable lnt = method.getLineNumberTable();
    	if (lnt==null) return -1;
    	LineNumber[] lns = lnt.getLineNumberTable();
    	if (lns==null || lns.length==0) return -1;
    	return lns[0].getLineNumber();
    }
	
	public void determineParameterNames() {
		if ((bitflags&PARAMETER_NAMES_INITIALIZED)!=0) return;
		bitflags|=PARAMETER_NAMES_INITIALIZED;
//		if (parameterNamesInitialized) return;
//		parameterNamesInitialized=true;
		LocalVariableTable varTable = method.getLocalVariableTable();
		int len = getArity();
		if (varTable == null) {
			setParameterNames(Utility.makeArgNames(len));
		} else {
			UnresolvedType[] paramTypes = getParameterTypes();
			String[] paramNames = new String[len];
			int index = isStatic() ? 0 : 1;
			for (int i = 0; i < len; i++) {
				LocalVariable lv = varTable.getLocalVariable(index);
				if (lv == null) {
					paramNames[i] = "arg" + i;
				} else {
					paramNames[i] = lv.getName();
				}
				index += paramTypes[i].getSize();
			}
			setParameterNames(paramNames);
		}
	}

	private void unpackAjAttributes(World world) {
//		associatedShadowMunger = null;
        List as = BcelAttributes.readAjAttributes(getDeclaringType().getClassName(),method.getAttributes(), getSourceContext(world),world,bcelObjectType.getWeaverVersionAttribute());
		processAttributes(world, as);
		as = AtAjAttributes.readAj5MethodAttributes(method, this, world.resolve(getDeclaringType()), getPreResolvedPointcutDefinition(),getSourceContext(world), world.getMessageHandler());
		processAttributes(world,as);
	}

	private void processAttributes(World world, List as) {
		for (Iterator iter = as.iterator(); iter.hasNext();) {
			AjAttribute a = (AjAttribute) iter.next();
			if (a instanceof AjAttribute.MethodDeclarationLineNumberAttribute) {
				addMetaData(MAPKEY_MD_LINE_NUMBER_ATTRIBUTE,a);
				bitflags|=HAS_MD_LINE_NUMBER_ATTRIBUTE;
//				declarationLineNumber = (AjAttribute.MethodDeclarationLineNumberAttribute)a;
			} else if (a instanceof AjAttribute.AdviceAttribute) {
				bitflags|=HAS_ASSOCIATED_SHADOWMUNGER;
				addMetaData(MAPKEY_ASSOCIATED_SHADOWMUNGER,((AjAttribute.AdviceAttribute)a).reify(this, world));
//				associatedShadowMunger = ((AjAttribute.AdviceAttribute)a).reify(this, world);
				// return;
			} else if (a instanceof AjAttribute.AjSynthetic) {
				bitflags|=IS_AJ_SYNTHETIC;
//				isAjSynthetic = true;
			} else if (a instanceof AjAttribute.EffectiveSignatureAttribute) {
				// System.out.println("found effective: " + this);
				bitflags|=HAS_EFFECTIVE_SIGNATURE;
				addMetaData(MAPKEY_EFFECTIVE_SIGNATURE,a);
//				effectiveSignature = (AjAttribute.EffectiveSignatureAttribute)a;
			} else if (a instanceof AjAttribute.PointcutDeclarationAttribute) {
				// this is an @AspectJ annotated advice method, with pointcut pre-resolved by ajc
				bitflags|=HAS_PRERESOLVED_POINTCUT;
				addMetaData(MAPKEY_PRERESOLVED_POINTCUT,((AjAttribute.PointcutDeclarationAttribute)a).reify());
//				preResolvedPointcut = ((AjAttribute.PointcutDeclarationAttribute)a).reify();
			} else {
				throw new BCException("weird method attribute " + a);
			}
		}
	}
	
	private void addMetaData(String k,Object v) {
		if (metaData==null) { metaData = new HashMap();}
		metaData.put(k,v);
	}
	
	// for testing - if we have this attribute, return it - will return null if it doesnt know anything 
	public AjAttribute[] getAttributes(String name) {
		List results = new ArrayList();
		List l = BcelAttributes.readAjAttributes(getDeclaringType().getClassName(),method.getAttributes(), getSourceContext(bcelObjectType.getWorld()),bcelObjectType.getWorld(),bcelObjectType.getWeaverVersionAttribute());
		for (Iterator iter = l.iterator(); iter.hasNext();) {
			AjAttribute element = (AjAttribute) iter.next();		
			if (element.getNameString().equals(name)) results.add(element);
		}
		if (results.size()>0) {
			return (AjAttribute[])results.toArray(new AjAttribute[]{});
		}
		return null;
	}
	
	// for testing - use with the method above
	public String[] getAttributeNames(boolean onlyIncludeAjOnes) {
		Attribute[] as = method.getAttributes();
		List names = new ArrayList();
		String[] strs = new String[as.length];
		for (int j = 0; j < as.length; j++) {
			if (!onlyIncludeAjOnes || as[j].getName().startsWith(AjAttribute.AttributePrefix))
			  names.add(as[j].getName());
		}
		return (String[])names.toArray(new String[]{});
	}

	public boolean isAjSynthetic() {
		return (bitflags&IS_AJ_SYNTHETIC)!=0;//isAjSynthetic; // || getName().startsWith(NameMangler.PREFIX);
	}
	
	//FIXME ??? needs an isSynthetic method
	
	public ShadowMunger getAssociatedShadowMunger() {
		if ((bitflags&HAS_ASSOCIATED_SHADOWMUNGER)==0) return null;
		return (ShadowMunger)metaData.get(MAPKEY_ASSOCIATED_SHADOWMUNGER);
//		return associatedShadowMunger;
	}
	
	public AjAttribute.EffectiveSignatureAttribute getEffectiveSignature() {
		if ((bitflags&HAS_EFFECTIVE_SIGNATURE)==0) return null;
		return (AjAttribute.EffectiveSignatureAttribute)metaData.get(MAPKEY_EFFECTIVE_SIGNATURE);//effectiveSignature;
	}

	public ResolvedPointcutDefinition getPreResolvedPointcutDefinition() {
		if ((bitflags&HAS_PRERESOLVED_POINTCUT)==0) return null;
		return (ResolvedPointcutDefinition)metaData.get(MAPKEY_PRERESOLVED_POINTCUT);//effectiveSignature;
	}
	
	public boolean hasDeclarationLineNumberInfo() {
		return ((bitflags&HAS_MD_LINE_NUMBER_ATTRIBUTE)!=0);
//		return declarationLineNumber != null;
	}
	
	public int getDeclarationLineNumber() {
		if ((bitflags&HAS_MD_LINE_NUMBER_ATTRIBUTE)!=0) {
			AjAttribute.MethodDeclarationLineNumberAttribute mdlna = (AjAttribute.MethodDeclarationLineNumberAttribute)metaData.get(MAPKEY_MD_LINE_NUMBER_ATTRIBUTE);
			return mdlna.getLineNumber();
		}
		return -1;
//		if (declarationLineNumber != null) {
//			return declarationLineNumber.getLineNumber();
//		} else {
//			return -1;
//		}
	}

    public int getDeclarationOffset() {
    	if ((bitflags&HAS_MD_LINE_NUMBER_ATTRIBUTE)!=0) {
			AjAttribute.MethodDeclarationLineNumberAttribute mdlna = (AjAttribute.MethodDeclarationLineNumberAttribute)metaData.get(MAPKEY_MD_LINE_NUMBER_ATTRIBUTE);
			return mdlna.getOffset();
		}
		return -1;
//        if (declarationLineNumber != null) {
//            return declarationLineNumber.getOffset();
//        } else {
//            return -1;
//        }
    }

    public ISourceLocation getSourceLocation() {
      ISourceLocation ret = super.getSourceLocation(); 
      if ((ret == null || ret.getLine()==0) && hasDeclarationLineNumberInfo()) {
        // lets see if we can do better
        ISourceContext isc = getSourceContext();
        if (isc !=null) ret = isc.makeSourceLocation(getDeclarationLineNumber(), getDeclarationOffset());
        else            ret = new SourceLocation(null,getDeclarationLineNumber());
      }
      return ret;
    }
	
	public Kind getKind() {
		if ((bitflags&HAS_ASSOCIATED_SHADOWMUNGER)!=0) {
			return ADVICE;
		} else {
			return super.getKind();
		}
	}
	
	public boolean hasAnnotation(UnresolvedType ofType) {
		ensureAnnotationTypesRetrieved();
		for (Iterator iter = annotationTypes.iterator(); iter.hasNext();) {
			ResolvedType aType = (ResolvedType) iter.next();
			if (aType.equals(ofType)) return true;		
		}
		return false;
	}
	
	public AnnotationX[] getAnnotations() {
		ensureAnnotationTypesRetrieved();
		if ((bitflags&HAS_ANNOTATIONS)!=0) {
			return (AnnotationX[])metaData.get(MAPKEY_ANNOTATIONS);
		} else {
			return AnnotationX.NONE;
		}
//		return annotations;
	}
	
	 public ResolvedType[] getAnnotationTypes() {
	    ensureAnnotationTypesRetrieved();
	    ResolvedType[] ret = new ResolvedType[annotationTypes.size()];
	    annotationTypes.toArray(ret);
	    return ret;
     }
	 
	 public void addAnnotation(AnnotationX annotation) {
	    ensureAnnotationTypesRetrieved();	
	    if ((bitflags&HAS_ANNOTATIONS)==0) {
			AnnotationX[] ret = new AnnotationX[1];
			ret[0]=annotation;
			addMetaData(MAPKEY_ANNOTATIONS,ret);
	    } else {
			// Add it to the set of annotations
	    	AnnotationX[] annotations = (AnnotationX[])metaData.get(MAPKEY_ANNOTATIONS);
			int len = annotations.length;
			AnnotationX[] ret = new AnnotationX[len+1];
			System.arraycopy(annotations, 0, ret, 0, len);
			ret[len] = annotation;
			addMetaData(MAPKEY_ANNOTATIONS,ret);
//			annotations = ret;
	    }
	    bitflags|=HAS_ANNOTATIONS;
		
		// Add it to the set of annotation types
	    if (annotationTypes==Collections.EMPTY_SET) annotationTypes = new HashSet();
		annotationTypes.add(UnresolvedType.forName(annotation.getTypeName()).resolve(bcelObjectType.getWorld()));
		// FIXME asc looks like we are managing two 'bunches' of annotations, one
		// here and one in the real 'method' - should we reduce it to one layer?
//		method.addAnnotation(annotation.getBcelAnnotation());
		// FIXME CUSTARD
	 }
	 
	 private void ensureAnnotationTypesRetrieved() {
		if (method == null) return; // must be ok, we have evicted it
		if ((bitflags&HAVE_DETERMINED_ANNOTATIONS)!=0) return;
		bitflags|=HAVE_DETERMINED_ANNOTATIONS;
//		if (annotationTypes == null) {// || method.getAnnotations().length!=annotations.length) { // sometimes the list changes underneath us!
    		AnnotationGen annos[] = method.getAnnotations();
    		if (annos.length!=0) {
	    		annotationTypes = new HashSet();
	    		AnnotationX[] annotations = new AnnotationX[annos.length];
	    		for (int i = 0; i < annos.length; i++) {
					AnnotationGen annotation = annos[i];
					annotationTypes.add(bcelObjectType.getWorld().resolve(UnresolvedType.forSignature(annotation.getTypeSignature())));
					annotations[i] = new AnnotationX(annotation,bcelObjectType.getWorld());
				}
	    		addMetaData(MAPKEY_ANNOTATIONS,annotations);
	    		bitflags|=HAS_ANNOTATIONS;
    		} else {
    			annotationTypes=Collections.EMPTY_SET;
    		}
//    		}
	}
	 

	 /**
	  * A method can be parameterized if it has one or more generic
	  * parameters. A generic parameter (type variable parameter) is
	  * identified by the prefix "T"
	  */
	 public boolean canBeParameterized() {
		 unpackGenericSignature();
		return (bitflags&CAN_BE_PARAMETERIZED)!=0;//canBeParameterized;
	}
	 
	 
	 public UnresolvedType[] getGenericParameterTypes() {
		 unpackGenericSignature();
		 if ((bitflags&HAS_GENERIC_RETPARAM_TYPES)==0) return getParameterTypes();
		 return (UnresolvedType[])metaData.get(MAPKEY_GENERIC_PARAM_TYPES);
//		 return genericParameterTypes;
	 }
	 
	 public UnresolvedType getGenericReturnType() {
		 unpackGenericSignature();
		 if ((bitflags&HAS_GENERIC_RETPARAM_TYPES)==0) return getReturnType();
		 return (UnresolvedType)metaData.get(MAPKEY_GENERIC_RETURN_TYPE);
//		 return genericReturnType;
	 }
	 
	 /** For testing only */
	 public Method getMethod() { return method; }
	 
	 private void unpackGenericSignature() {
		if ((bitflags&UNPACKED_GENERIC_SIGNATURE)!=0) return;
		bitflags|=UNPACKED_GENERIC_SIGNATURE;
//		 if (unpackedGenericSignature) return;
//		 unpackedGenericSignature = true;
 		 if (!bcelObjectType.getWorld().isInJava5Mode()) { 
 			 
// 			 this.genericReturnType = getReturnType();
// 			 this.genericParameterTypes = getParameterTypes();
 			 return;
 		 }
		 String gSig = method.getGenericSignature();
		 if (gSig != null) {
			 Signature.MethodTypeSignature mSig = new GenericSignatureParser().parseAsMethodSignature(gSig);//method.getGenericSignature());
 			 if (mSig.formalTypeParameters.length > 0) {
				// generic method declaration
 				bitflags|=CAN_BE_PARAMETERIZED;
//				canBeParameterized = true;
			 }
 			 
 			typeVariables = new TypeVariable[mSig.formalTypeParameters.length];
	    	  for (int i = 0; i < typeVariables.length; i++) {
				Signature.FormalTypeParameter methodFtp = mSig.formalTypeParameters[i];
				try {
					typeVariables[i] = BcelGenericSignatureToTypeXConverter.formalTypeParameter2TypeVariable(
							methodFtp, 
							mSig.formalTypeParameters,
							bcelObjectType.getWorld());
				} catch (GenericSignatureFormatException e) {
					// this is a development bug, so fail fast with good info
					throw new IllegalStateException(
							"While getting the type variables for method " + this.toString()
							+ " with generic signature " + mSig + 
							" the following error condition was detected: " + e.getMessage());
				}
			  }
 			 
 			 Signature.FormalTypeParameter[] parentFormals = bcelObjectType.getAllFormals();
 			 Signature.FormalTypeParameter[] formals = new
 			 	Signature.FormalTypeParameter[parentFormals.length + mSig.formalTypeParameters.length];
 			 // put method formal in front of type formals for overriding in lookup
 			 System.arraycopy(mSig.formalTypeParameters,0,formals,0,mSig.formalTypeParameters.length);
 			 System.arraycopy(parentFormals,0,formals,mSig.formalTypeParameters.length,parentFormals.length);
 			 Signature.TypeSignature returnTypeSignature = mSig.returnType;
			 bitflags|=HAS_GENERIC_RETPARAM_TYPES;
			 UnresolvedType genericReturnType = null;
			 UnresolvedType[] genericParameterTypes = null;
			 try {
				genericReturnType = BcelGenericSignatureToTypeXConverter.typeSignature2TypeX(
						 returnTypeSignature, formals,
						 bcelObjectType.getWorld());
			} catch (GenericSignatureFormatException e) {
//				 development bug, fail fast with good info
				throw new IllegalStateException(
						"While determing the generic return type of " + this.toString()
						+ " with generic signature " + gSig + " the following error was detected: "
						+ e.getMessage());
			}
			 Signature.TypeSignature[] paramTypeSigs = mSig.parameters;
			 genericParameterTypes = new UnresolvedType[paramTypeSigs.length];
			 for (int i = 0; i < paramTypeSigs.length; i++) {
				try {
					genericParameterTypes[i] = 
						BcelGenericSignatureToTypeXConverter.typeSignature2TypeX(
								paramTypeSigs[i],formals,bcelObjectType.getWorld());
				} catch (GenericSignatureFormatException e) {
//					 development bug, fail fast with good info
					throw new IllegalStateException(
							"While determining the generic parameter types of " + this.toString()
							+ " with generic signature " + gSig + " the following error was detected: "
							+ e.getMessage());
				}
				if (paramTypeSigs[i] instanceof TypeVariableSignature) {
					bitflags|=CAN_BE_PARAMETERIZED;
//					canBeParameterized = true;
				}
			 }
			 addMetaData(MAPKEY_GENERIC_PARAM_TYPES, genericParameterTypes);
			 addMetaData(MAPKEY_GENERIC_RETURN_TYPE, genericReturnType);
			 bitflags|=HAS_GENERIC_RETPARAM_TYPES;
		 } 
//		 else {
//			 genericReturnType = getReturnType();
//			 genericParameterTypes = getParameterTypes();
//		 }
	 }
	 
	 public void evictWeavingState() {
		 if (method != null) {
			 unpackGenericSignature();
			 unpackJavaAttributes();
			 ensureAnnotationTypesRetrieved();
			 determineParameterNames();
// 			 this.sourceContext = SourceContextImpl.UNKNOWN_SOURCE_CONTEXT;
			 method = null;
		 }
	 }

	public boolean isSynthetic() {
		if ((bitflags&KNOW_IF_SYNTHETIC)==0) {
			workOutIfSynthetic();
		}
		return (bitflags&IS_SYNTHETIC)!=0;//isSynthetic;
	}

	// Pre Java5 synthetic is an attribute 'Synthetic', post Java5 it is a modifier (4096 or 0x1000)
	private void workOutIfSynthetic() {
		if ((bitflags&KNOW_IF_SYNTHETIC)!=0) return;
		bitflags|=KNOW_IF_SYNTHETIC;
//		knowIfSynthetic=true;
		JavaClass jc = bcelObjectType.getJavaClass();
	    bitflags&=IS_SYNTHETIC_INVERSE; // unset the bit
//		isSynthetic=false;
		if (jc==null) return; // what the hell has gone wrong?
		if (jc.getMajor()<49/*Java5*/) {
			// synthetic is an attribute
			String[] synthetics =  getAttributeNames(false);
			if (synthetics!=null) {
				for (int i = 0; i < synthetics.length; i++) {
					if (synthetics[i].equals("Synthetic")) {
						bitflags|=IS_SYNTHETIC;
//						isSynthetic=true;
						break;}
				}
			}
		} else {
			// synthetic is a modifier (4096)
			if ((modifiers&4096)!=0) {
				bitflags|=IS_SYNTHETIC;
			}
//			isSynthetic = (modifiers&4096)!=0;
		}
	}

	/**
	 * Returns whether or not the given object is equivalent to the
	 * current one. Returns true if getMethod().getCode().getCodeString()
	 * are equal. Allows for different line number tables.
	 */
	// bug 154054: is similar to equals(Object) however
	// doesn't require implementing equals in Method and Code
	// which proved expensive. Currently used within 
	// CrosscuttingMembers.replaceWith() to decide if we need
	// to do a full build
	public boolean isEquivalentTo(Object other) {	
		if(! (other instanceof BcelMethod)) return false;
		BcelMethod o = (BcelMethod)other;
		return getMethod().getCode().getCodeString().equals(
				o.getMethod().getCode().getCodeString());
	}

}
