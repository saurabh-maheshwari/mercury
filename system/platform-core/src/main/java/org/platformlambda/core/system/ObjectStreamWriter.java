/*

    Copyright 2018-2019 Accenture Technology

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

 */

package org.platformlambda.core.system;

import org.platformlambda.core.exception.AppException;
import org.platformlambda.core.models.Kv;
import org.platformlambda.core.services.ObjectStreamService;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeoutException;

public class ObjectStreamWriter implements Closeable {

    private static final String TYPE = ObjectStreamService.TYPE;
    private static final String WRITE = ObjectStreamService.WRITE;
    private static final String EOF = ObjectStreamService.EOF;

    private String streamId;
    private long timeout = 8000;
    private boolean closed = false;

    public ObjectStreamWriter(String streamId) {
        this.streamId = streamId;
    }

    public void setWriteTimeout(long timeoutMs) {
        this.timeout = timeoutMs;
    }

    public long getWriteTimeout() {
        return timeout;
    }

    public void write(Object payload) throws IOException {
        if (payload == null) {
            // writing a null payload also indicates EOF
            close();
        } else {
            try {
                // use RPC request to guarantee that the payload is written to disk
                PostOffice.getInstance().request(streamId, timeout, payload, new Kv(TYPE, WRITE));
            } catch (TimeoutException | AppException e) {
                throw new IOException(e.getMessage());
            }
        }
    }

    public void write(byte[] payload, int start, int end) throws IOException {
        if (start >= end) {
            throw new IOException("start must be less than end pointer. Actual: start/end="+start+"/"+end);
        }
        if (end > payload.length) {
            throw new IOException("end pointer must not be larger than payload buffer size");
        }
        if (start == 0 && end == payload.length) {
            write(payload);
        } else {
            write(Arrays.copyOfRange(payload, start, end));
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            PostOffice.getInstance().send(streamId, new Kv(TYPE, EOF));
        }
    }

}


