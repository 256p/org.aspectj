/* *******************************************************************
 * Copyright (c) 1999-2001 Xerox Corporation,
 *               2002 Palo Alto Research Center, Incorporated (PARC).
 * All rights reserved.
 * This program and the accompanying materials are made available
 * under the terms of the Eclipse Public License v 2.0
 * which accompanies this distribution and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.txt
 *
 * Contributors:
 *     Xerox/PARC     initial implementation
 * ******************************************************************/


package org.aspectj.lang.reflect;

import org.aspectj.lang.Signature;

public interface MemberSignature extends Signature {
	// AccessibleObject is a 1.2 API, we run on 1.1... (thanks Wes for catching this)
	//AccessibleObject getAccessibleObject();
}
