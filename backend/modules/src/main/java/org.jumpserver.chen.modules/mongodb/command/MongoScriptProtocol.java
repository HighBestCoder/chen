package org.jumpserver.chen.modules.mongodb.command;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.bson.Document;

/** Length framed messages, so script strings can never inject additional operations. */
final class MongoScriptProtocol {
    static final int MAX_BYTES=8*1024*1024;
    static Document read(InputStream input) throws IOException {
        DataInputStream in=new DataInputStream(input);int length=in.readInt();
        if(length<0||length>MAX_BYTES)throw new IOException("Script message exceeds 8 MiB");
        byte[] bytes=in.readNBytes(length);if(bytes.length!=length)throw new EOFException();
        return Document.parse(new String(bytes,StandardCharsets.UTF_8));
    }
    static void write(OutputStream output,Document message) throws IOException {
        byte[] bytes=message.toJson().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES)throw new IOException("Script message exceeds 8 MiB");
        DataOutputStream out=new DataOutputStream(output);out.writeInt(bytes.length);out.write(bytes);out.flush();
    }
}
