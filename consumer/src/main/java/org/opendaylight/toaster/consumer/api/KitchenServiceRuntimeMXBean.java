/*
 * Copyright (c) 2016 Brocade Communications Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.toaster.consumer.api;

/**
 * MXBean interface that provides attributes and operations for the kitchen service via JMX.
 *
 * @author Thomas Pantelis
 */
public interface KitchenServiceRuntimeMXBean {
    Boolean makeScrambledWithWheat();
}
