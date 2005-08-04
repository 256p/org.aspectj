/* *******************************************************************
 * Copyright (c) 2005 Contributors.
 * All rights reserved. 
 * This program and the accompanying materials are made available 
 * under the terms of the Eclipse Public License v1.0 
 * which accompanies this distribution and is available at 
 * http://eclipse.org/legal/epl-v10.html 
 *  
 * Contributors: 
 *   Adrian Colyer			Initial implementation
 * ******************************************************************/
package org.aspectj.weaver;

import java.util.ArrayList;
import java.util.List;

import org.aspectj.weaver.UnresolvedType.TypeKind;

/**
 * @author colyer
 *
 */
public class TypeFactory {

	/**
	 * Create a parameterized version of a generic type.
	 * @param aGenericType
	 * @param someTypeParameters note, in the case of an inner type of a parameterized type,
	 *         this parameter may legitimately be null
	 * @param inAWorld
	 * @return
	 */
	public static ReferenceType createParameterizedType(
		ResolvedType aBaseType,
		UnresolvedType[] someTypeParameters,
		World inAWorld
	) {
		ResolvedType baseType = aBaseType;
		if (!aBaseType.isGenericType()) {
			// try and find the generic type...
			if (someTypeParameters != null && someTypeParameters.length>0) {
				if (!aBaseType.isRawType()) throw new IllegalStateException("Expecting raw type");
				baseType = baseType.getGenericType();
				if (baseType == null) throw new IllegalStateException("Raw type does not have generic type set");
			} // else if someTypeParameters is null, then the base type is allowed to be non-generic, it's an inner
		}
		ResolvedType[] resolvedParameters = inAWorld.resolve(someTypeParameters);
		ReferenceType pType = new ReferenceType(baseType,resolvedParameters,inAWorld);
		pType.setSourceContext(aBaseType.getSourceContext());
		return (ReferenceType) pType.resolve(inAWorld);
	}
	
	/**
	 * Create an *unresolved* parameterized version of a generic type.
	 */
	public static UnresolvedType createUnresolvedParameterizedType(String sig,String erasuresig,UnresolvedType[] arguments) {
	  return new UnresolvedType(sig,erasuresig,arguments);
	}

	public static ReferenceType createRawType(
			ResolvedType aBaseType,
			World inAWorld
		) {
		if (aBaseType.isRawType()) return (ReferenceType) aBaseType;
		if (!aBaseType.isGenericType()) {
			if (!aBaseType.isRawType()) throw new IllegalStateException("Expecting generic type");
		}
		ReferenceType rType = new ReferenceType(aBaseType,inAWorld);
		rType.setSourceContext(aBaseType.getSourceContext());
		return (ReferenceType) rType.resolve(inAWorld);
	}
	
	
	/**
	 * Used by UnresolvedType.read, creates a type from a full signature.
	 * @param signature
	 * @return
	 */
	public static UnresolvedType createTypeFromSignature(String signature) {
		if (signature.equals(ResolvedType.MISSING_NAME)) return ResolvedType.MISSING;
		
		if (signature.startsWith(ResolvedType.PARAMETERIZED_TYPE_IDENTIFIER)) {
			// parameterized type, calculate signature erasure and type parameters
			int startOfParams = signature.indexOf('<');
			int endOfParams = signature.lastIndexOf('>');
			String signatureErasure = "L" + signature.substring(1,startOfParams) + ";";
			UnresolvedType[] typeParams = createTypeParams(signature.substring(startOfParams +1, endOfParams));
			return new UnresolvedType(signature,signatureErasure,typeParams);
		} else if (signature.equals("?")){
			UnresolvedType ret = UnresolvedType.SOMETHING;
			ret.typeKind = TypeKind.WILDCARD;
			return ret;
		} else if(signature.startsWith("+")) {
			// ? extends ...
			UnresolvedType bound = UnresolvedType.forSignature(signature.substring(1));
			UnresolvedType ret = new UnresolvedType(signature);
			ret.typeKind = TypeKind.WILDCARD;
			ret.setUpperBound(bound);
			return ret;
		} else if (signature.startsWith("-")) {
			// ? super ...
			UnresolvedType bound = UnresolvedType.forSignature(signature.substring(1));
			UnresolvedType ret = new UnresolvedType(signature);
			ret.typeKind = TypeKind.WILDCARD;
			ret.setLowerBound(bound);
			return ret;
		}
		return new UnresolvedType(signature);
	}
	
	private static UnresolvedType[] createTypeParams(String typeParameterSpecification) {
		String remainingToProcess = typeParameterSpecification;
		List types = new ArrayList();
		while(!remainingToProcess.equals("")) {
			int endOfSig = 0;
			int anglies = 0;
			boolean sigFound = false;
			for (endOfSig = 0; (endOfSig < remainingToProcess.length()) && !sigFound; endOfSig++) {
				char thisChar = remainingToProcess.charAt(endOfSig);
				switch(thisChar) {
				case '<' : anglies++; break;
				case '>' : anglies--; break;
				case ';' : 
					if (anglies == 0) {
						sigFound = true;
						break;
					}
				}
			}
			types.add(createTypeFromSignature(remainingToProcess.substring(0,endOfSig)));
			remainingToProcess = remainingToProcess.substring(endOfSig);
		}
		UnresolvedType[] typeParams = new UnresolvedType[types.size()];
		types.toArray(typeParams);
		return typeParams;
	}
}
