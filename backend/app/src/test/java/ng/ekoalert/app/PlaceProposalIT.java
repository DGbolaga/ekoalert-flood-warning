package ng.ekoalert.app;

import ng.ekoalert.domain.model.DrainageEdge;
import ng.ekoalert.domain.model.ProposedPlace;
import ng.ekoalert.domain.model.Reporter;
import ng.ekoalert.domain.model.Zone;
import ng.ekoalert.domain.repo.ProposedPlaceRepository;
import ng.ekoalert.domain.repo.ProposedPlaceVoiceRepository;
import ng.ekoalert.domain.service.PlaceOutcome;
import ng.ekoalert.domain.service.PlaceProposalService;
import ng.ekoalert.engine.Confidence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Residents naming places the seed never had.
 *
 * <p>The rules under test are the ones that stop a single person, or a person
 * with a vague memory, from putting a node on a map other people trust: a place
 * needs separate voices, it needs a position before it can be drawn or timed,
 * and the edge it arrives with is inferred, so naming a place warns nobody.
 */
class PlaceProposalIT extends IntegrationTestBase {

    @Autowired PlaceProposalService places;
    @Autowired ProposedPlaceRepository proposedPlaces;
    @Autowired ProposedPlaceVoiceRepository placeVoices;

    /**
     * Far enough from every fixture zone to be a genuinely new node, close enough
     * to the origin to be a plausible next place for water to reach. Fixture
     * zones sit at 6.53 + n/100 by 3.38 + n/100, so this lands roughly 800 m
     * from Z01.
     */
    private static final double NEW_LAT = 6.545;
    private static final double NEW_LNG = 3.395;

    @Test
    @DisplayName("one voice names a place but does not put it on the map")
    void singleVoiceDoesNotPromote() {
        zone("Z01");
        Reporter ada = verifiedReporter("ada", "Z01");

        PlaceOutcome outcome = places.propose("Alapere Bus Stop", NEW_LAT, NEW_LNG, "Z01", ada.getId(), NOON);

        assertThat(outcome.promoted()).isFalse();
        assertThat(outcome.distinctVoices()).isEqualTo(1);
        assertThat(outcome.zone()).isNull();
        assertThat(zones.findAllByOrderByIdAsc()).extracting(Zone::getId).containsExactly("Z01");
        assertThat(edges.findAll()).isEmpty();
    }

    @Test
    @DisplayName("a second separate resident promotes it, and the edge arrives inferred")
    void secondVoicePromotes() {
        zone("Z01");
        Reporter ada = verifiedReporter("ada", "Z01");
        Reporter bola = verifiedReporter("bola", "Z01");

        PlaceOutcome first = places.propose("Alapere Bus Stop", NEW_LAT, NEW_LNG, "Z01", ada.getId(), NOON);
        PlaceOutcome second = places.affirm(first.place().getId(), null, null, bola.getId(), NOON);

        assertThat(second.promoted()).isTrue();
        assertThat(second.distinctVoices()).isEqualTo(2);
        assertThat(second.mergedInto()).isFalse();

        Zone created = second.zone();
        assertThat(created.getId()).isEqualTo("Z02");
        assertThat(created.getLandmark()).isEqualTo("Alapere Bus Stop");
        assertThat(created.getSource()).isEqualTo(Zone.RESIDENT);
        // The name came from a person, so it is not waiting on a field survey.
        assertThat(created.isNeedsFieldNaming()).isFalse();
        // displayName falls through to the landmark, never to an invented name.
        assertThat(created.displayName()).isEqualTo("Alapere Bus Stop");

        DrainageEdge edge = second.edge();
        assertThat(edge.getFromZone()).isEqualTo("Z01");
        assertThat(edge.getToZone()).isEqualTo("Z02");
        // The whole point: a resident naming a place does not make it alertable.
        assertThat(edge.getConfidence()).isEqualTo(Confidence.INFERRED);
        assertThat(edge.getInferenceBasis()).contains("resident proposal");
        assertThat(edge.getTravelMinutes()).isPositive();
    }

    @Test
    @DisplayName("a place nobody has located never promotes, however many voices it has")
    void positionGatesPromotion() {
        zone("Z01");
        Reporter ada = verifiedReporter("ada", "Z01");
        Reporter bola = verifiedReporter("bola", "Z01");

        // Somebody knows the name but is not standing there.
        PlaceOutcome first = places.propose("Alapere Bus Stop", null, null, "Z01", ada.getId(), NOON);
        assertThat(first.place().isLocated()).isFalse();

        PlaceOutcome second = places.affirm(first.place().getId(), null, null, bola.getId(), NOON);

        assertThat(second.distinctVoices()).isEqualTo(2);
        assertThat(second.promoted()).isFalse();
        assertThat(zones.findAllByOrderByIdAsc()).hasSize(1);
    }

    @Test
    @DisplayName("an affirmation supplies the position the proposal lacked, and that promotes it")
    void affirmationCanSupplyPosition() {
        zone("Z01");
        Reporter ada = verifiedReporter("ada", "Z01");
        Reporter bola = verifiedReporter("bola", "Z01");

        PlaceOutcome first = places.propose("Alapere Bus Stop", null, null, "Z01", ada.getId(), NOON);
        PlaceOutcome second = places.affirm(first.place().getId(), NEW_LAT, NEW_LNG, bola.getId(), NOON);

        assertThat(second.promoted()).isTrue();
        assertThat(second.zone().getLocation().getY()).isEqualTo(NEW_LAT);
        assertThat(second.zone().getLocation().getX()).isEqualTo(NEW_LNG);
    }

    @Test
    @DisplayName("the same person affirming twice is still one voice")
    void repeatedVoiceDoesNotCount() {
        zone("Z01");
        Reporter ada = verifiedReporter("ada", "Z01");

        PlaceOutcome first = places.propose("Alapere Bus Stop", NEW_LAT, NEW_LNG, "Z01", ada.getId(), NOON);
        PlaceOutcome again = places.affirm(first.place().getId(), null, null, ada.getId(), NOON);

        assertThat(again.distinctVoices()).isEqualTo(1);
        assertThat(again.promoted()).isFalse();
        assertThat(zones.findAllByOrderByIdAsc()).hasSize(1);
    }

    @Test
    @DisplayName("a place beside an existing zone names that zone rather than duplicating it")
    void nearbyPlaceMergesAndNamesTheExistingZone() {
        zone("Z01");
        Zone z02 = zone("Z02");
        Reporter ada = verifiedReporter("ada", "Z01");
        Reporter bola = verifiedReporter("bola", "Z01");
        assertThat(z02.getLandmark()).isNull();

        // Roughly 110 m from Z02, inside the merge radius. Residents calling a
        // seeded zone by the name they actually use is the field survey happening.
        double lat = z02.getLocation().getY() + 0.001;
        double lng = z02.getLocation().getX();

        PlaceOutcome first = places.propose("Ogudu Ori-Oke", lat, lng, "Z01", ada.getId(), NOON);
        PlaceOutcome second = places.affirm(first.place().getId(), null, null, bola.getId(), NOON);

        assertThat(second.promoted()).isTrue();
        assertThat(second.mergedInto()).isTrue();
        assertThat(second.zone().getId()).isEqualTo("Z02");
        assertThat(zones.findAllByOrderByIdAsc()).hasSize(2);

        Zone renamed = zones.findById("Z02").orElseThrow();
        assertThat(renamed.getLandmark()).isEqualTo("Ogudu Ori-Oke");
        assertThat(renamed.displayName()).isEqualTo("Ogudu Ori-Oke");
        // It was merged into, not created, so its provenance is still the seed.
        assertThat(renamed.getSource()).isEqualTo(Zone.SEED);
    }

    @Test
    @DisplayName("a merge onto an already named zone leaves the existing name alone")
    void mergeDoesNotOverwriteAName() {
        zone("Z01");
        Zone z02 = zone("Z02");
        z02.setLandmark("Surveyed Name");
        zones.save(z02);
        Reporter ada = verifiedReporter("ada", "Z01");
        Reporter bola = verifiedReporter("bola", "Z01");

        PlaceOutcome first = places.propose("Something Else",
                z02.getLocation().getY() + 0.001, z02.getLocation().getX(), "Z01", ada.getId(), NOON);
        places.affirm(first.place().getId(), null, null, bola.getId(), NOON);

        assertThat(zones.findById("Z02").orElseThrow().getLandmark()).isEqualTo("Surveyed Name");
    }

    @Test
    @DisplayName("promoting twice onto the same zone reuses the edge instead of failing")
    void promotionIsIdempotentOnTheEdge() {
        zone("Z01");
        Reporter ada = verifiedReporter("ada", "Z01");
        Reporter bola = verifiedReporter("bola", "Z01");
        Reporter chidi = verifiedReporter("chidi", "Z01");

        PlaceOutcome first = places.propose("Alapere Bus Stop", NEW_LAT, NEW_LNG, "Z01", ada.getId(), NOON);
        places.affirm(first.place().getId(), null, null, bola.getId(), NOON);

        // A second, separate proposal for the same spot lands inside the merge
        // radius of the zone the first one created.
        PlaceOutcome other = places.propose("Alapere", NEW_LAT, NEW_LNG, "Z01", bola.getId(), NOON);
        PlaceOutcome promoted = places.affirm(other.place().getId(), null, null, chidi.getId(), NOON);

        assertThat(promoted.promoted()).isTrue();
        assertThat(promoted.mergedInto()).isTrue();
        assertThat(promoted.zone().getId()).isEqualTo("Z02");
        assertThat(edges.findAll()).hasSize(1);
        assertThat(zones.findAllByOrderByIdAsc()).hasSize(2);
    }

    @Test
    @DisplayName("the proposal is kept whether or not it was ever promoted")
    void everyProposalIsLogged() {
        zone("Z01");
        Reporter ada = verifiedReporter("ada", "Z01");

        places.propose("Alapere Bus Stop", NEW_LAT, NEW_LNG, "Z01", ada.getId(), NOON);

        Optional<ProposedPlace> stored = proposedPlaces.findAll().stream().findFirst();
        assertThat(stored).isPresent();
        assertThat(stored.get().getProposedBy()).isEqualTo(ada.getId());
        assertThat(stored.get().getProposedAt()).isEqualTo(NOON);
        assertThat(stored.get().getStatus()).isEqualTo(ProposedPlace.PENDING);
    }

    @Test
    @DisplayName("a rejected proposal is kept, not deleted")
    void rejectionKeepsTheRow() {
        zone("Z01");
        Reporter ada = verifiedReporter("ada", "Z01");
        PlaceOutcome first = places.propose("Nowhere", NEW_LAT, NEW_LNG, "Z01", ada.getId(), NOON);

        places.reject(first.place().getId(), NOON);

        ProposedPlace stored = proposedPlaces.findById(first.place().getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ProposedPlace.REJECTED);
        assertThat(places.pending()).isEmpty();
    }

    @Test
    @DisplayName("a spot on top of the origin is refused, not recorded as water reaching itself")
    void placeOnTheOriginIsRefused() {
        zone("Z01");
        Reporter ada = verifiedReporter("ada", "Z01");
        Zone z01 = zones.findById("Z01").orElseThrow();

        // What you get by opening the map picker and sending without moving it.
        assertThatThrownBy(() -> places.propose("Right Here",
                z01.getLocation().getY(), z01.getLocation().getX(), "Z01", ada.getId(), NOON))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Z01 itself");

        assertThat(proposedPlaces.findAll()).isEmpty();
    }

    @Test
    @DisplayName("a spot far downstream is refused rather than becoming a zone")
    void absurdlyDistantPlaceIsRefused() {
        zone("Z01");
        Reporter ada = verifiedReporter("ada", "Z01");
        Zone z01 = zones.findById("Z01").orElseThrow();

        // Roughly 12 km south: the shape of a phone reporting where its owner is
        // standing rather than where the place is.
        double lat = z01.getLocation().getY() - 0.112;
        double lng = z01.getLocation().getX();

        assertThatThrownBy(() -> places.propose("Somewhere Far", lat, lng, "Z01", ada.getId(), NOON))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too far downstream");

        assertThat(zones.findAllByOrderByIdAsc()).hasSize(1);
        assertThat(proposedPlaces.findAll()).isEmpty();
    }

    @Test
    @DisplayName("an affirmation cannot smuggle in a position the proposal could not have")
    void distantPositionIsRefusedOnAffirmToo() {
        zone("Z01");
        Reporter ada = verifiedReporter("ada", "Z01");
        Reporter bola = verifiedReporter("bola", "Z01");
        Zone z01 = zones.findById("Z01").orElseThrow();

        PlaceOutcome first = places.propose("Somewhere", null, null, "Z01", ada.getId(), NOON);

        assertThatThrownBy(() -> places.affirm(first.place().getId(),
                z01.getLocation().getY() - 0.112, z01.getLocation().getX(), bola.getId(), NOON))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too far downstream");
    }

    @Test
    @DisplayName("supplying the position later credits the voice already on record")
    void locatingLaterMarksTheExistingVoice() {
        zone("Z01");
        Reporter ada = verifiedReporter("ada", "Z01");

        PlaceOutcome first = places.propose("Alapere Bus Stop", null, null, "Z01", ada.getId(), NOON);
        assertThat(placeVoices.findByPlaceId(first.place().getId()))
                .singleElement()
                .satisfies(voice -> assertThat(voice.isLocated()).isFalse());

        // Same person, back with a position. He does not become a second voice,
        // but the record should show that he is the one who pinned it.
        places.affirm(first.place().getId(), NEW_LAT, NEW_LNG, ada.getId(), NOON.plusSeconds(60));

        assertThat(placeVoices.findByPlaceId(first.place().getId()))
                .singleElement()
                .satisfies(voice -> assertThat(voice.isLocated()).isTrue());
        assertThat(places.voiceCount(first.place().getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("a place needs a name and a real origin zone")
    void inputIsValidated() {
        zone("Z01");
        Reporter ada = verifiedReporter("ada", "Z01");

        assertThatThrownBy(() -> places.propose("   ", NEW_LAT, NEW_LNG, "Z01", ada.getId(), NOON))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recognise");

        assertThatThrownBy(() -> places.propose("Somewhere", NEW_LAT, NEW_LNG, "ZZZ", ada.getId(), NOON))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown zone");
    }
}
