/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.qpid.jms.provider.amqp.message;

import static org.apache.qpid.jms.provider.amqp.message.AmqpMessageSupport.SERIALIZED_JAVA_OBJECT_CONTENT_TYPE;
import static org.apache.qpid.jms.provider.amqp.message.AmqpMessageSupport.decodeMessage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.qpid.jms.util.QpidTrustingObjectInputStream;
import org.apache.qpid.jms.util.QpidTrustingObjectInputStream.TrustedClassFilter;
import org.apache.qpid.proton.amqp.Binary;
import org.apache.qpid.proton.amqp.messaging.Data;
import org.apache.qpid.proton.amqp.messaging.Section;
import org.apache.qpid.proton.message.Message;

import io.netty.buffer.ByteBuf;

/**
 * Wrapper around an AMQP Message instance that will be treated as a JMS ObjectMessage
 * type.
 */
public class AmqpSerializedObjectDelegate implements AmqpObjectTypeDelegate {

    static final Data NULL_OBJECT_BODY;
    static
    {
        byte[] bytes;
        try {
            bytes = getSerializedBytes(null);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialise null object body", e);
        }

        NULL_OBJECT_BODY = new Data(new Binary(bytes));
    }

    private final Message message;
    private final AtomicReference<Section> cachedReceivedBody = new AtomicReference<Section>();
    private ByteBuf messageBytes;

    /**
     * Create a new delegate that uses Java serialization to store the message content.
     *
     * @param message
     *        the AMQP message instance where the object is to be stored / read.
     * @param messageBytes
     *        the raw bytes that comprise the message when it was received.
     */
    public AmqpSerializedObjectDelegate(Message message, ByteBuf messageBytes) {
        this.message = message;
        this.message.setContentType(SERIALIZED_JAVA_OBJECT_CONTENT_TYPE);
        this.messageBytes = messageBytes;

        // We will decode the body on each access, so clear the current value
        // so we don't carry along unneeded bloat.
        if (messageBytes != null) {
            cachedReceivedBody.set(message.getBody());
        }
    }

    private static byte[] getSerializedBytes(Serializable value) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {

            oos.writeObject(value);
            oos.flush();
            oos.close();

            return baos.toByteArray();
        }
    }

    @Override
    public Serializable getObject() throws IOException, ClassNotFoundException {
        Binary bin = null;

        Section body = cachedReceivedBody.getAndSet(null);
        if (body == null) {
            if (messageBytes != null) {
                body = decodeMessage(messageBytes).getBody();
            } else {
                body = message.getBody();
            }
        }

        if (body == null || body == NULL_OBJECT_BODY) {
            return null;
        } else if (body instanceof Data) {
            bin = ((Data) body).getValue();
        } else {
            throw new IllegalStateException("Unexpected body type: " + body.getClass().getSimpleName());
        }

        if (bin == null) {
            return null;
        } else {
            Serializable serialized = null;

            TrustedClassFilter filter = new TrustedClassFilter() {
                @Override
                public boolean isTrusted(Class<?> clazz) {
                    return clazz.getPackage().getName().startsWith("org.apache.");
                }
            };

            try (ByteArrayInputStream bais = new ByteArrayInputStream(bin.getArray(), bin.getArrayOffset(), bin.getLength());
                 QpidTrustingObjectInputStream objIn = new QpidTrustingObjectInputStream(bais, filter)) {

                serialized = (Serializable) objIn.readObject();
            }

            return serialized;
        }
    }

    @Override
    public void setObject(Serializable value) throws IOException {
        cachedReceivedBody.set(null);

        if (value == null) {
            message.setBody(NULL_OBJECT_BODY);
        } else {
            byte[] bytes = getSerializedBytes(value);
            message.setBody(new Data(new Binary(bytes)));
        }

        messageBytes = null;
    }

    @Override
    public void onSend() {
        message.setContentType(SERIALIZED_JAVA_OBJECT_CONTENT_TYPE);
        if (message.getBody() == null) {
            message.setBody(NULL_OBJECT_BODY);
        }
    }

    @Override
    public boolean isAmqpTypeEncoded() {
        return false;
    }
}
