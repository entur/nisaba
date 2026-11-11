/*
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 *   https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 *
 */

package no.entur.nisaba.pipeline;

import no.entur.nisaba.event.NetexImportEventKeyFactory;
import no.entur.nisaba.services.NisabaExchangeBlobStoreService;
import org.junit.jupiter.api.Test;
import org.rutebanken.helper.storage.repository.InMemoryBlobStoreRepository;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Optional;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChouetteImportKeyResolverTest {

    private static final LocalDateTime OLDER = LocalDateTime.of(2021, 6, 4, 10, 10);
    private static final LocalDateTime MIDDLE = LocalDateTime.of(2021, 6, 5, 10, 10);
    private static final LocalDateTime NEWER = LocalDateTime.of(2021, 6, 6, 10, 10);

    private final InMemoryBlobStoreRepository repository = new InMemoryBlobStoreRepository(new HashMap<>());
    private final ChouetteImportKeyResolver resolver = new ChouetteImportKeyResolver(
            new NisabaExchangeBlobStoreService("nisaba-exchange", repository), "nisaba-exchange");

    private void imported(String codespace, LocalDateTime created) {
        repository.uploadBlob("imported/" + codespace + "/" + NetexImportEventKeyFactory.createNetexImportEventKey(codespace, created) + ".zip",
                new ByteArrayInputStream("test".getBytes()));
    }

    private static TreeSet<LocalDateTime> all() {
        return new TreeSet<>(java.util.List.of(OLDER, MIDDLE, NEWER));
    }

    @Test
    void theOneThatExistsWins() {
        imported("avi", MIDDLE);
        assertEquals(Optional.of("avi_2021-06-05T10_10_00"), resolver.resolve("avi", all()));
    }

    @Test
    void theMostRecentOfSeveralWins() {
        imported("avi", OLDER);
        imported("avi", NEWER);
        assertEquals(Optional.of("avi_2021-06-06T10_10_00"), resolver.resolve("avi", all()));
    }

    @Test
    void onlyTheOlderExists() {
        imported("avi", OLDER);
        assertEquals(Optional.of("avi_2021-06-04T10_10_00"), resolver.resolve("avi", all()));
    }

    @Test
    void noneExists() {
        imported("other", MIDDLE);
        assertEquals(Optional.empty(), resolver.resolve("avi", all()));
    }
}
