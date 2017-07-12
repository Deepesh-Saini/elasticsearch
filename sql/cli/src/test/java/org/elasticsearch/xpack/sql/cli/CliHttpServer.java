/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.cli;

import org.elasticsearch.client.Client;
import org.elasticsearch.xpack.sql.protocol.shared.Response;
import org.elasticsearch.xpack.sql.test.server.ProtoHttpServer;

/**
 * Internal server used for testing without starting a new Elasticsearch instance.
 */
public class CliHttpServer extends ProtoHttpServer<Response> {
    public CliHttpServer(Client client) {
        super(client, new CliProtoHandler(client), "/_cli");
    }

    @Override
    public String url() {
        return "http://" + super.url();
    }
}