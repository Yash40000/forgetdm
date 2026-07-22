package io.forgetdm.mainframe;

import io.forgetdm.core.copybook.Copybook;
import io.forgetdm.core.copybook.RecordCodec;
import io.forgetdm.core.copybook.RecordValue;
import io.forgetdm.core.copybook.codec.Ebcdic;
import io.forgetdm.mainframe.transport.TransportFactory;
import io.forgetdm.provision.SyntheticGenService;
import io.forgetdm.security.OwnershipGuard;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MainframeGenServiceTest {

    @Test
    void qualifiedGeneratorPathsPopulateCodecRelativeFields() {
        String source = String.join("\n",
                "01 CRF-CUSTOMER-RECORD.",
                "   05 CRF-KEY    PIC X(14).",
                "   05 NAME-GROUP.",
                "      10 FIRST-NAME PIC X(20).");
        CopybookDefEntity def = new CopybookDefEntity();
        def.setId(7L);
        def.setName("crf-customer");
        def.setSource(source);
        def.setCodePage("Cp037");

        CopybookDefRepository copybooks = mock(CopybookDefRepository.class);
        MainframeConnectionRepository connections = mock(MainframeConnectionRepository.class);
        TransportFactory transports = mock(TransportFactory.class);
        SyntheticGenService synth = mock(SyntheticGenService.class);
        when(copybooks.findById(7L)).thenReturn(Optional.of(def));

        LinkedHashMap<String, String> generated = new LinkedHashMap<>();
        generated.put("CRF-CUSTOMER-RECORD.CRF-KEY", "CRF-0000000001");
        generated.put("CRF-CUSTOMER-RECORD.NAME-GROUP.FIRST-NAME", "Alex");
        when(synth.generateRows(any(), eq(1L), eq(42L))).thenReturn(List.of(generated));

        MainframeGenService service = new MainframeGenService(copybooks, connections, transports, synth,
                mock(io.forgetdm.audit.AuditService.class), mock(OwnershipGuard.class));
        MainframeGenService.GenFileReq request = new MainframeGenService.GenFileReq(
                7L, "Cp037", "FB", 42L, 1L,
                List.of(
                        new MainframeGenService.GenFileColumn("CRF-CUSTOMER-RECORD.CRF-KEY", "PADDED_SEQUENCE", "10", "CRF-"),
                        new MainframeGenService.GenFileColumn("CRF-CUSTOMER-RECORD.NAME-GROUP.FIRST-NAME", "FIRST_NAME", "US", "ANY")
                ),
                "DOWNLOAD", null, null);

        Map<String, Object> result = service.generateFile(request);
        byte[] bytes = Base64.getDecoder().decode(String.valueOf(result.get("postBase64")));
        Copybook copybook = CopybookSupport.parse(source);
        RecordValue decoded = new RecordCodec(copybook.primaryRecord(), new Ebcdic("Cp037")).decode(bytes);

        assertEquals("CRF-0000000001", decoded.get("CRF-KEY").value().trim());
        assertEquals("Alex", decoded.get("NAME-GROUP.FIRST-NAME").value().trim());
    }
}
