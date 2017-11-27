/*
 * Copyright © 2017 cyy and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.toaster.cli.impl;

import org.opendaylight.controller.md.sal.binding.api.DataBroker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.opendaylight.toaster.cli.api.ToasterCliCommands;

public class ToasterCliCommandsImpl implements ToasterCliCommands {

    private static final Logger LOG = LoggerFactory.getLogger(ToasterCliCommandsImpl.class);
    private final DataBroker dataBroker;

    public ToasterCliCommandsImpl(final DataBroker db) {
        this.dataBroker = db;
        LOG.info("ToasterCliCommandImpl initialized");
    }

    @Override
    public Object testCommand(Object testArgument) {
        return "This is a test implementation of test-command";
    }
}