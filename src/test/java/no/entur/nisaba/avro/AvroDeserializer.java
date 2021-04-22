package no.entur.nisaba.avro;

import org.apache.avro.io.DatumReader;
import org.apache.avro.io.Decoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;

import java.io.IOException;

public class AvroDeserializer {

    public static NetexImportEvent deSerializeAvroNetexImportEvent(byte[] data) throws IOException {
        DatumReader<NetexImportEvent> reader
                = new SpecificDatumReader<>(NetexImportEvent.class);
        Decoder decoder = DecoderFactory.get().binaryDecoder(data, null);
        return reader.read(null, decoder);
    }
}
