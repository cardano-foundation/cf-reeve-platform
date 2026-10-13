package org.cardanofoundation.lob.app.blockchain_publisher.service.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Counterparty.Type.VENDOR;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType.FxRevaluation;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType.Journal;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import co.nstant.in.cbor.CborException;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadataList;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_common.domain.events.DocumentPublishCommand;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.DocumentMetadataSerialiser;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports.ReportEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.EventMilestoneAllocationEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.EventProjectAllocationEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.SpendingEventEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.CostCenter;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Counterparty;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Currency;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Document;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Project;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionItemEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Vat;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.report.API3MetadataSerialiser;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.spendingevent.SpendingEventMetadataSerialiser;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.transaction.API1MetadataSerialiser;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.reporting.model.enums.DataMode;
import org.cardanofoundation.lob.app.reporting.model.enums.IntervalType;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;

/**
 * Byte-level safety net for the shared-metadata-section extraction. These assertions pin the exact CBOR emitted today by
 * the four 1447 serialisers for the fixtures below; any refactor that changes on-chain output for one
 * of these shapes must fail here first.
 *
 * <p><b>What is pinned.</b> {@code DocumentMetadataSerialiser}: a single document with two content
 * slots. {@code API3MetadataSerialiser} (reports): a single flat report-data entry, and separately a
 * two-section, two-level-deep nested report (exercising both the section {@code _o} field-order
 * convention and the leaf {@code v}/{@code _o} convention together). {@code API1MetadataSerialiser}
 * (transactions): a single collapsable transaction/item; the same transaction when the batch
 * organisation is NOT collapsable; a two-transaction collapsable batch; a two-transaction batch
 * spanning two DIFFERENT organisations (non-collapsable — each transaction's own nested {@code org}
 * is asserted explicitly, not just pinned as opaque bytes); and a single transaction with two items.
 * {@code SpendingEventMetadataSerialiser}: a single event with a sub-project allocation, and
 * separately a two-event bundle mixing a sub-project allocation with a direct (non-sub-project) one.
 *
 * <p><b>What is NOT pinned.</b> Batches of three or more elements (transactions, items, events,
 * milestones, or report-data keys at a given nesting level); report data nested more than two levels
 * deep; a spending-event bundle spanning more than one organisation (the serialiser assumes a single
 * shared {@code org} per batch and does not defend against a mixed one — see its Javadoc); and entity
 * fields left null/absent, which the existing per-serialiser unit tests ({@code
 * DocumentMetadataSerialiserTest}, {@code API3MetadataSerialiserTest}, {@code
 * API1MetadataSerialiserTest}, {@code SpendingEventMetadataSerialiserTest}) already exercise
 * structurally (field-by-field), not byte-for-byte.
 *
 * <p>Collections in these fixtures are built with {@code LinkedHashSet}/{@code LinkedHashMap} and an
 * explicit insertion order, never {@code Set.of(...)}/{@code Map.of(...)} with two or more entries:
 * those have JVM-run-salted iteration order, which would make the pinned bytes flaky across runs.
 * This matters for {@code Set<TransactionEntity>}, {@code Set<TransactionItemEntity>} and {@code
 * Set<SpendingEventEntity>} because each is serialised into a CBOR array (list) in iteration order,
 * and list element order is preserved on the wire, not resorted. Plain {@link
 * com.bloxbean.cardano.client.metadata.MetadataMap} object fields (e.g. {@code org}, or a report-data
 * section) are unaffected either way — canonical CBOR encoding sorts map keys by encoded length at
 * write time regardless of {@code put} order, see {@link
 * org.cardanofoundation.lob.app.blockchain_common.service_assistance.L1MetadataSections}'s Javadoc — but {@code LinkedHashMap}
 * is still used for the report-data fixtures below for consistency and readability.
 *
 * <p>Fixtures are copied (and, for the new multi-entity cases, extended) from the existing
 * per-serialiser unit tests: {@code DocumentMetadataSerialiserTest}, {@code
 * API3MetadataSerialiserTest}, {@code API1MetadataSerialiserTest} and {@code
 * SpendingEventMetadataSerialiserTest}. This class does not refactor those tests — it is fully
 * self-contained.
 *
 * <p>The expected hex literals below were produced by running this test against unmodified
 * production code and pasting the actual value from the assertion failure — that is the intended
 * workflow, not a shortcut.
 */
class CborCharacterizationTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final long CREATION_SLOT = 1_000_000L;

    private static final String DOCUMENT_EXPECTED_CBOR_HEX =
            "a4636f7267a5626964656f72672d31646e616d656441636d656b63757272656e63795f69646c49534f5f343231373a434846"
            + "6c636f756e7472795f636f64656243486d7461785f69645f6e756d626572655441582d316464617461a762696465646f632d"
            + "3168697066735f63696471516d4669786564436964466f72546573746a736c6f745f636f756e74026c636f6e74656e745f68"
            + "6173687840616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161"
            + "616161616161616161616161616161616161616e706c61696e746578745f6861736878406262626262626262626262626262"
            + "6262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262"
            + "70656e76656c6f70655f76657273696f6e0174726563697069656e745f6b65795f6861736865738278403330306339633936"
            + "3033623932613462333965643339353862663932343031313438303464623466643337333031326330636134373433326436"
            + "3334323561657840663335653536313631363061333062663363366537396661373363353736643430323035653866633362"
            + "61346531633664636639336536623938653835376234647479706568444f43554d454e54686d65746164617461a167766572"
            + "73696f6e63312e31";
    private static final String REPORT_EXPECTED_CBOR_HEX =
            "aa636f7267a5626964666f7267313233646e616d657241636d65205265706f7274696e67204f72676b63757272656e63795f"
            + "6964635553446c636f756e7472795f636f64656255536d7461785f69645f6e756d626572655441582d326376657201646461"
            + "7461a167746573743132336135646d6f64656653595354454d6474797065665245504f525464796561726432303234667065"
            + "72696f640167737562547970656d42414c414e43455f534845455468696e74657276616c6459454152686d65746164617461"
            + "a36776657273696f6e63312e326974696d657374616d7074323032362d30312d30315430303a30303a30305a6d6372656174"
            + "696f6e5f736c6f741a000f4240";
    private static final String TRANSACTION_COLLAPSABLE_EXPECTED_CBOR_HEX =
            "a4636f7267a5626964666f7267313233646e616d657154657374204f7267616e69736174696f6e6b63757272656e63795f69"
            + "64635553446c636f756e7472795f636f64656255536d7461785f69645f6e756d626572693132333435363738396464617461"
            + "81a762696465747831323364646174656a323032332d30322d313564747970656d4678526576616c756174696f6e65697465"
            + "6d7381a7626964656974656d3166616d6f756e74633130306766785f7261746561316770726f6a656374a2646e616d656653"
            + "756d6d697469637573745f636f64656e414e20303030303031203230323368646f63756d656e74a463766174a26472617465"
            + "65302e30333869637573745f636f64656943482d56482d332e38666e756d62657264646f63316863757272656e6379a26269"
            + "64f669637573745f636f6465635553446c636f756e7465727061727479a264747970656656454e444f5269637573745f636f"
            + "6465694350203030303030316a616d6f756e745f6c6379633130306b636f73745f63656e746572a2646e616d656b436f7374"
            + "2043656e74657269637573745f636f646569434320303030303031666e756d62657261316862617463685f69646662617463"
            + "6831716163636f756e74696e675f706572696f6467323032332d3032647479706577494e444956494455414c5f5452414e53"
            + "414354494f4e53686d65746164617461a36776657273696f6e63312e316974696d657374616d7074323032362d30312d3031"
            + "5430303a30303a30305a6d6372656174696f6e5f736c6f741a000f4240";
    private static final String TRANSACTION_NON_COLLAPSABLE_EXPECTED_CBOR_HEX =
            "a3646461746181a8626964657478313233636f7267a5626964666f7267313233646e616d657154657374204f7267616e6973"
            + "6174696f6e6b63757272656e63795f6964635553446c636f756e7472795f636f64656255536d7461785f69645f6e756d6265"
            + "726931323334353637383964646174656a323032332d30322d313564747970656d4678526576616c756174696f6e65697465"
            + "6d7381a7626964656974656d3166616d6f756e74633130306766785f7261746561316770726f6a656374a2646e616d656653"
            + "756d6d697469637573745f636f64656e414e20303030303031203230323368646f63756d656e74a463766174a26472617465"
            + "65302e30333869637573745f636f64656943482d56482d332e38666e756d62657264646f63316863757272656e6379a26269"
            + "64f669637573745f636f6465635553446c636f756e7465727061727479a264747970656656454e444f5269637573745f636f"
            + "6465694350203030303030316a616d6f756e745f6c6379633130306b636f73745f63656e746572a2646e616d656b436f7374"
            + "2043656e74657269637573745f636f646569434320303030303031666e756d62657261316862617463685f69646662617463"
            + "6831716163636f756e74696e675f706572696f6467323032332d3032647479706577494e444956494455414c5f5452414e53"
            + "414354494f4e53686d65746164617461a36776657273696f6e63312e316974696d657374616d7074323032362d30312d3031"
            + "5430303a30303a30305a6d6372656174696f6e5f736c6f741a000f4240";
    private static final String SPENDING_EVENT_EXPECTED_CBOR_HEX =
            "a4636f7267a5626964666f7267313233646e616d657154657374204f7267616e69736174696f6e6b63757272656e63795f69"
            + "646c49534f5f343231373a4348466c636f756e7472795f636f64656243486d7461785f69645f6e756d626572693132333435"
            + "36373839646461746181af626964666576656e743164646174656a323032352d30342d303364686173686a646f632d686173"
            + "682d316474797065685350454e44494e47656e6f7465736a496e766f6963652023316676656e646f726956656e646f722041"
            + "426766785f7261746564302e38356a616c6c6f636174696f6e81a36a70726f6a6563745f69646a50726f6a6563744944316b"
            + "7375625f70726f6a656374a36a6d696c6573746f6e657381a36c6d696c6573746f6e655f6964636d73316f6d696c6573746f"
            + "6e655f7469746c656c4d696c6573746f6e6520414270616c6c6f63617465645f616d6f756e746238356e7375625f70726f6a"
            + "6563745f69646d53756250726f6a656374494431717375625f70726f6a6563745f7469746c656f53756250726f6a65637454"
            + "69746c656d70726f6a6563745f7469746c656c50726f6a6563745469746c656a616d6f756e745f666379633130306a616d6f"
            + "756e745f7263796238356a66756e64696e675f69646566756e64316a66756e64696e675f747864667478316c63757272656e"
            + "63795f666379a26269646c49534f5f343231373a45555269637573745f636f6465634555526c63757272656e63795f726379"
            + "a26269646c49534f5f343231373a55534469637573745f636f646563555344717370656e64696e675f63617465676f727969"
            + "506572736f6e6e656c64747970656746554e44494e47686d65746164617461a36776657273696f6e63312e306974696d6573"
            + "74616d7074323032362d30312d30315430303a30303a30305a6d6372656174696f6e5f736c6f741a000f4240";

    // The five hex literals below are captured, not guessed (see class Javadoc): each started as an
    // obviously-wrong placeholder, the test was run once to fail, and the ACTUAL value from the
    // assertion failure was pasted in verbatim.
    private static final String REPORT_MULTI_KEY_NESTED_EXPECTED_CBOR_HEX =
            "aa636f7267a5626964666f7267313233646e616d657241636d65205265706f7274696e67204f72676b63757272656e63795f6964"
            + "635553446c636f756e7472795f636f64656255536d7461785f69645f6e756d626572655441582d3263766572016464617461a266"
            + "617373657473a2625f6f006e63757272656e745f617373657473a3625f6f006463617368a261766431303030625f6f0073616363"
            + "6f756e74735f72656365697661626c65a261766432303030625f6f016b6c696162696c6974696573a2625f6f017363757272656e"
            + "745f6c696162696c6974696573a2625f6f00706163636f756e74735f70617961626c65a261766431353030625f6f00646d6f6465"
            + "6653595354454d6474797065665245504f52546479656172643230323466706572696f640167737562547970656d42414c414e43"
            + "455f534845455468696e74657276616c6459454152686d65746164617461a36776657273696f6e63312e326974696d657374616d"
            + "7074323032362d30312d30315430303a30303a30305a6d6372656174696f6e5f736c6f741a000f4240";
    private static final String TRANSACTION_MULTI_BATCH_COLLAPSABLE_EXPECTED_CBOR_HEX =
            "a4636f7267a5626964666f7267313233646e616d657154657374204f7267616e69736174696f6e6b63757272656e63795f696463"
            + "5553446c636f756e7472795f636f64656255536d7461785f69645f6e756d62657269313233343536373839646461746182a76269"
            + "6465747831323364646174656a323032332d30322d313564747970656d4678526576616c756174696f6e656974656d7381a76269"
            + "64656974656d3166616d6f756e74633130306766785f7261746561316770726f6a656374a2646e616d656653756d6d6974696375"
            + "73745f636f64656e414e20303030303031203230323368646f63756d656e74a463766174a2647261746565302e30333869637573"
            + "745f636f64656943482d56482d332e38666e756d62657264646f63316863757272656e6379a2626964f669637573745f636f6465"
            + "635553446c636f756e7465727061727479a264747970656656454e444f5269637573745f636f6465694350203030303030316a61"
            + "6d6f756e745f6c6379633130306b636f73745f63656e746572a2646e616d656b436f73742043656e74657269637573745f636f64"
            + "6569434320303030303031666e756d62657261316862617463685f696466626174636831716163636f756e74696e675f70657269"
            + "6f6467323032332d3032a762696465747834353664646174656a323032332d30332d32306474797065674a6f75726e616c656974"
            + "656d7381a7626964656974656d3266616d6f756e74633235306766785f7261746561316770726f6a656374a2646e616d65674576"
            + "657265737469637573745f636f64656e414e20303030303032203230323368646f63756d656e74a463766174a264726174656530"
            + "2e30373769637573745f636f64656943482d56482d372e37666e756d62657264646f63326863757272656e6379a2626964f66963"
            + "7573745f636f6465634555526c636f756e7465727061727479a264747970656656454e444f5269637573745f636f646569435020"
            + "3030303030326a616d6f756e745f6c6379633235306b636f73745f63656e746572a2646e616d656f436f73742043656e74657220"
            + "54776f69637573745f636f646569434320303030303032666e756d62657261326862617463685f69646662617463683271616363"
            + "6f756e74696e675f706572696f6467323032332d3033647479706577494e444956494455414c5f5452414e53414354494f4e5368"
            + "6d65746164617461a36776657273696f6e63312e316974696d657374616d7074323032362d30312d30315430303a30303a30305a"
            + "6d6372656174696f6e5f736c6f741a000f4240";
    private static final String TRANSACTION_MULTI_BATCH_DIFFERENT_ORGS_EXPECTED_CBOR_HEX =
            "a3646461746182a8626964657478313233636f7267a5626964666f7267313233646e616d657154657374204f7267616e69736174"
            + "696f6e6b63757272656e63795f6964635553446c636f756e7472795f636f64656255536d7461785f69645f6e756d626572693132"
            + "3334353637383964646174656a323032332d30322d313564747970656d4678526576616c756174696f6e656974656d7381a76269"
            + "64656974656d3166616d6f756e74633130306766785f7261746561316770726f6a656374a2646e616d656653756d6d6974696375"
            + "73745f636f64656e414e20303030303031203230323368646f63756d656e74a463766174a2647261746565302e30333869637573"
            + "745f636f64656943482d56482d332e38666e756d62657264646f63316863757272656e6379a2626964f669637573745f636f6465"
            + "635553446c636f756e7465727061727479a264747970656656454e444f5269637573745f636f6465694350203030303030316a61"
            + "6d6f756e745f6c6379633130306b636f73745f63656e746572a2646e616d656b436f73742043656e74657269637573745f636f64"
            + "6569434320303030303031666e756d62657261316862617463685f696466626174636831716163636f756e74696e675f70657269"
            + "6f6467323032332d3032a8626964657478373839636f7267a5626964666f7267393939646e616d65735365636f6e64204f726761"
            + "6e69736174696f6e6b63757272656e63795f6964634555526c636f756e7472795f636f64656244456d7461785f69645f6e756d62"
            + "65726939383736353433323164646174656a323032332d30352d30356474797065674a6f75726e616c656974656d7381a5626964"
            + "656974656d3366616d6f756e74633330306766785f72617465613168646f63756d656e74a2666e756d62657264646f6333686375"
            + "7272656e6379a2626964f669637573745f636f6465634555526a616d6f756e745f6c637963333030666e756d6265726133686261"
            + "7463685f696466626174636833716163636f756e74696e675f706572696f6467323032332d3035647479706577494e4449564944"
            + "55414c5f5452414e53414354494f4e53686d65746164617461a36776657273696f6e63312e316974696d657374616d7074323032"
            + "362d30312d30315430303a30303a30305a6d6372656174696f6e5f736c6f741a000f4240";
    private static final String TRANSACTION_MULTI_ITEM_EXPECTED_CBOR_HEX =
            "a4636f7267a5626964666f7267313233646e616d657154657374204f7267616e69736174696f6e6b63757272656e63795f696463"
            + "5553446c636f756e7472795f636f64656255536d7461785f69645f6e756d62657269313233343536373839646461746181a76269"
            + "646d74782d6d756c74692d6974656d64646174656a323032332d30342d313064747970656d4678526576616c756174696f6e6569"
            + "74656d7382a6626964666974656d2d6166616d6f756e746235306766785f7261746561316770726f6a656374a2646e616d65624b"
            + "3269637573745f636f64656e414e20303030303033203230323368646f63756d656e74a3666e756d62657265646f632d61686375"
            + "7272656e6379a2626964f669637573745f636f6465635553446c636f756e7465727061727479a264747970656656454e444f5269"
            + "637573745f636f6465694350203030303030336a616d6f756e745f6c6379623530a6626964666974656d2d6266616d6f756e7462"
            + "37356766785f72617465613168646f63756d656e74a2666e756d62657265646f632d626863757272656e6379a2626964f6696375"
            + "73745f636f6465635553446a616d6f756e745f6c63796237356b636f73745f63656e746572a2646e616d6571436f73742043656e"
            + "74657220546872656569637573745f636f646569434320303030303033666e756d62657261346862617463685f69646662617463"
            + "6834716163636f756e74696e675f706572696f6467323032332d3034647479706577494e444956494455414c5f5452414e534143"
            + "54494f4e53686d65746164617461a36776657273696f6e63312e316974696d657374616d7074323032362d30312d30315430303a"
            + "30303a30305a6d6372656174696f6e5f736c6f741a000f4240";
    private static final String SPENDING_EVENT_MULTI_EVENT_EXPECTED_CBOR_HEX =
            "a4636f7267a5626964666f7267313233646e616d657154657374204f7267616e69736174696f6e6b63757272656e63795f69646c"
            + "49534f5f343231373a4348466c636f756e7472795f636f64656243486d7461785f69645f6e756d62657269313233343536373839"
            + "646461746182af626964666576656e743164646174656a323032352d30342d303364686173686a646f632d686173682d31647479"
            + "7065685350454e44494e47656e6f7465736a496e766f6963652023316676656e646f726956656e646f722041426766785f726174"
            + "6564302e38356a616c6c6f636174696f6e81a36a70726f6a6563745f69646a50726f6a6563744944316b7375625f70726f6a6563"
            + "74a36a6d696c6573746f6e657381a36c6d696c6573746f6e655f6964636d73316f6d696c6573746f6e655f7469746c656c4d696c"
            + "6573746f6e6520414270616c6c6f63617465645f616d6f756e746238356e7375625f70726f6a6563745f69646d53756250726f6a"
            + "656374494431717375625f70726f6a6563745f7469746c656f53756250726f6a6563745469746c656d70726f6a6563745f746974"
            + "6c656c50726f6a6563745469746c656a616d6f756e745f666379633130306a616d6f756e745f7263796238356a66756e64696e67"
            + "5f69646566756e64316a66756e64696e675f747864667478316c63757272656e63795f666379a26269646c49534f5f343231373a"
            + "45555269637573745f636f6465634555526c63757272656e63795f726379a26269646c49534f5f343231373a5553446963757374"
            + "5f636f646563555344717370656e64696e675f63617465676f727969506572736f6e6e656caf626964666576656e743264646174"
            + "656a323032352d30352d313264686173686a646f632d686173682d326474797065685350454e44494e47656e6f7465736a496e76"
            + "6f6963652023326676656e646f726956656e646f722043446766785f7261746564302e38356a616c6c6f636174696f6e81a36a6d"
            + "696c6573746f6e657381a36c6d696c6573746f6e655f6964636d73326f6d696c6573746f6e655f7469746c656c4d696c6573746f"
            + "6e6520434470616c6c6f63617465645f616d6f756e74633137306a70726f6a6563745f69646a50726f6a6563744944326d70726f"
            + "6a6563745f7469746c656d50726f6a6563745469746c65326a616d6f756e745f666379633230306a616d6f756e745f7263796331"
            + "37306a66756e64696e675f69646566756e64326a66756e64696e675f747864667478326c63757272656e63795f666379a2626964"
            + "6c49534f5f343231373a47425069637573745f636f6465634742506c63757272656e63795f726379a26269646c49534f5f343231"
            + "373a45555269637573745f636f646563455552717370656e64696e675f63617465676f72796654726176656c6474797065674655"
            + "4e44494e47686d65746164617461a36776657273696f6e63312e306974696d657374616d7074323032362d30312d30315430303a"
            + "30303a30305a6d6372656174696f6e5f736c6f741a000f4240";

    private static String hex(MetadataMap map) throws CborException {
        return HexFormat.of().formatHex(CborSerializationUtil.serialize(map.getMap()));
    }

    // ---------------------------------------------------------------------------------------
    // DOCUMENT (DocumentMetadataSerialiser) — fixture copied from DocumentMetadataSerialiserTest
    // ---------------------------------------------------------------------------------------

    @Test
    void documentManifestCborIsUnchanged() throws CborException {
        DocumentMetadataSerialiser serialiser = new DocumentMetadataSerialiser();

        MetadataMap map = serialiser.serialiseToMetadataMap(documentPublishCommandFixture(), "QmFixedCidForTest",
                "org-1", "Acme", "TAX-1", "ISO_4217:CHF", "CH");

        assertThat(hex(map)).isEqualTo(DOCUMENT_EXPECTED_CBOR_HEX);
    }

    private static DocumentPublishCommand documentPublishCommandFixture() {
        return new DocumentPublishCommand(
                "org-1",
                "doc-1",
                1,
                "a".repeat(64),
                "b".repeat(64),
                "c".repeat(24),
                "Y2lwaGVydGV4dA==",
                List.of(
                        new DocumentPublishCommand.PublishSlot("d".repeat(64), "e".repeat(96), "300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae"),
                        new DocumentPublishCommand.PublishSlot("f".repeat(64), "0".repeat(96), "f35e5616160a30bf3c6e79fa73c576d40205e8fc3ba4e1c6dcf93e6b98e857b4")),
                null, null);
    }

    // ---------------------------------------------------------------------------------------
    // REPORT (API3MetadataSerialiser) — fixture copied from API3MetadataSerialiserTest
    // (single report-data entry, to keep the CBOR deterministic — see class Javadoc)
    // ---------------------------------------------------------------------------------------

    @Test
    void reportManifestCborIsUnchanged() throws CborException {
        OrganisationPublicApi organisationPublicApi = mock(OrganisationPublicApi.class);
        when(organisationPublicApi.findByOrganisationId("org123")).thenReturn(Optional.of(reportOrganisationFixture()));
        API3MetadataSerialiser serialiser = new API3MetadataSerialiser(organisationPublicApi, FIXED);

        MetadataMap map = serialiser.serialiseToMetadataMap(reportEntityFixture(), CREATION_SLOT);

        assertThat(hex(map)).isEqualTo(REPORT_EXPECTED_CBOR_HEX);
    }

    /**
     * Two top-level sections ("Assets", "Liabilities"), one nested a level deeper
     * ("CurrentAssets"/"CurrentLiabilities" each holding two leaves), exercising the section {@code
     * _o} field-order convention and the leaf {@code v}/{@code _o} convention together, with more
     * than one key at every level. Built with {@code LinkedHashMap} (not {@code Map.of}) so the
     * multi-key maps keep an explicit, JVM-run-stable insertion order — see class Javadoc for why
     * this is precautionary rather than strictly required for {@code MetadataMap} fields.
     */
    @Test
    void reportManifestCborIsUnchanged_multiKeyNestedReportData() throws CborException {
        OrganisationPublicApi organisationPublicApi = mock(OrganisationPublicApi.class);
        when(organisationPublicApi.findByOrganisationId("org123")).thenReturn(Optional.of(reportOrganisationFixture()));
        API3MetadataSerialiser serialiser = new API3MetadataSerialiser(organisationPublicApi, FIXED);

        MetadataMap map = serialiser.serialiseToMetadataMap(multiKeyNestedReportEntityFixture(), CREATION_SLOT);

        assertThat(hex(map)).isEqualTo(REPORT_MULTI_KEY_NESTED_EXPECTED_CBOR_HEX);
    }

    private static ReportEntity reportEntityFixture() {
        ReportEntity reportEntity = new ReportEntity();
        reportEntity.setId("report-v2-001");
        reportEntity.setPeriod((short) 1);
        reportEntity.setOrganisationId("org123");
        reportEntity.setReportData(Map.of("Test123", 5));
        reportEntity.setYear((short) 2024);
        reportEntity.setIntervalType(IntervalType.YEAR);
        reportEntity.setReportTemplateType(ReportTemplateType.BALANCE_SHEET);
        reportEntity.setDataMode(DataMode.SYSTEM);
        reportEntity.setReportVer(1L);
        return reportEntity;
    }

    private static Organisation reportOrganisationFixture() {
        return Organisation.builder()
                .id("org123")
                .name("Acme Reporting Org")
                .taxIdNumber("TAX-2")
                .countryCode("US")
                .accountPeriodDays(365)
                .currencyId("USD")
                .reportCurrencyId("USD")
                .build();
    }

    private static ReportEntity multiKeyNestedReportEntityFixture() {
        ReportEntity reportEntity = new ReportEntity();
        reportEntity.setId("report-v2-001");
        reportEntity.setPeriod((short) 1);
        reportEntity.setOrganisationId("org123");
        reportEntity.setReportData(multiKeyNestedReportDataFixture());
        reportEntity.setYear((short) 2024);
        reportEntity.setIntervalType(IntervalType.YEAR);
        reportEntity.setReportTemplateType(ReportTemplateType.BALANCE_SHEET);
        reportEntity.setDataMode(DataMode.SYSTEM);
        reportEntity.setReportVer(1L);
        return reportEntity;
    }

    private static Map<String, Object> multiKeyNestedReportDataFixture() {
        Map<String, Object> cash = new LinkedHashMap<>();
        cash.put("v", 1000);
        cash.put("_o", 0);

        Map<String, Object> accountsReceivable = new LinkedHashMap<>();
        accountsReceivable.put("v", 2000);
        accountsReceivable.put("_o", 1);

        Map<String, Object> currentAssets = new LinkedHashMap<>();
        currentAssets.put("_o", 0);
        currentAssets.put("Cash", cash);
        currentAssets.put("AccountsReceivable", accountsReceivable);

        Map<String, Object> assets = new LinkedHashMap<>();
        assets.put("_o", 0);
        assets.put("CurrentAssets", currentAssets);

        Map<String, Object> accountsPayable = new LinkedHashMap<>();
        accountsPayable.put("v", 1500);
        accountsPayable.put("_o", 0);

        Map<String, Object> currentLiabilities = new LinkedHashMap<>();
        currentLiabilities.put("_o", 0);
        currentLiabilities.put("AccountsPayable", accountsPayable);

        Map<String, Object> liabilities = new LinkedHashMap<>();
        liabilities.put("_o", 1);
        liabilities.put("CurrentLiabilities", currentLiabilities);

        Map<String, Object> reportData = new LinkedHashMap<>();
        reportData.put("Assets", assets);
        reportData.put("Liabilities", liabilities);
        return reportData;
    }

    // ---------------------------------------------------------------------------------------
    // TRANSACTION (API1MetadataSerialiser) — fixture copied from API1MetadataSerialiserTest,
    // trimmed to a single transaction / single item so the fixture is a singleton collection
    // (avoids relying on Set iteration order for the byte-level assertion).
    // ---------------------------------------------------------------------------------------

    @Test
    void transactionManifestCborIsUnchanged_organisationCollapsable() throws CborException {
        API1MetadataSerialiser serialiser = new API1MetadataSerialiser(FIXED);

        MetadataMap map = serialiser.serialiseToMetadataMap("org123", Set.of(transactionFixture()), CREATION_SLOT);

        assertThat(hex(map)).isEqualTo(TRANSACTION_COLLAPSABLE_EXPECTED_CBOR_HEX);
    }

    /**
     * Non-collapsable branch: the dispatch organisationId differs from the (single) transaction's
     * organisation, so {@code org} is omitted at the top level and nested per-transaction instead —
     * the branch most likely to be broken by a refactor.
     */
    @Test
    void transactionManifestCborIsUnchanged_organisationNotCollapsable() throws CborException {
        API1MetadataSerialiser serialiser = new API1MetadataSerialiser(FIXED);

        MetadataMap map = serialiser.serialiseToMetadataMap("org-dispatch-other", Set.of(transactionFixture()), CREATION_SLOT);

        assertThat(hex(map)).isEqualTo(TRANSACTION_NON_COLLAPSABLE_EXPECTED_CBOR_HEX);
    }

    /**
     * A two-transaction batch, both belonging to the dispatch organisation, so {@code org} is
     * collapsed to the top level as usual. Uses {@code LinkedHashSet} with an explicit insertion
     * order (not {@code Set.of(...)}) so which transaction lands at CBOR array index 0 vs. 1 is
     * fixed, not JVM-salted — see class Javadoc.
     */
    @Test
    void transactionManifestCborIsUnchanged_multiTransactionBatch_organisationCollapsable() throws CborException {
        API1MetadataSerialiser serialiser = new API1MetadataSerialiser(FIXED);

        Set<TransactionEntity> transactions = new LinkedHashSet<>(List.of(transactionFixture(), secondTransactionFixture()));

        MetadataMap map = serialiser.serialiseToMetadataMap("org123", transactions, CREATION_SLOT);

        assertThat(hex(map)).isEqualTo(TRANSACTION_MULTI_BATCH_COLLAPSABLE_EXPECTED_CBOR_HEX);
    }

    /**
     * A two-transaction batch where the transactions belong to two DIFFERENT organisations (not just
     * different from the dispatch id): {@code isOrganisationCollapsable} requires every transaction's
     * organisation to match the dispatch id, so this batch is non-collapsable and each transaction
     * nests its own {@code org} instead of a single top-level one. In addition to pinning the exact
     * bytes, this test decodes the produced {@link MetadataMap} and asserts each transaction carries
     * its own correct organisation — the structural guarantee the byte pin alone doesn't spell out.
     */
    @Test
    void transactionManifestCborIsUnchanged_multiTransactionBatch_differentOrganisationsNotCollapsable() throws CborException {
        API1MetadataSerialiser serialiser = new API1MetadataSerialiser(FIXED);

        TransactionEntity txOrg123 = transactionFixture();
        TransactionEntity txOrg999 = thirdTransactionFixtureOtherOrganisation();
        Set<TransactionEntity> transactions = new LinkedHashSet<>(List.of(txOrg123, txOrg999));

        MetadataMap map = serialiser.serialiseToMetadataMap("org123", transactions, CREATION_SLOT);

        assertThat(map.get("org")).isNull();

        CBORMetadataList txList = (CBORMetadataList) map.get("data");
        MetadataMap tx1Map = (MetadataMap) txList.getValueAt(0);
        MetadataMap tx2Map = (MetadataMap) txList.getValueAt(1);

        MetadataMap tx1Org = (MetadataMap) tx1Map.get("org");
        assertThat(tx1Org.get("id")).isEqualTo("org123");
        assertThat(tx1Org.get("name")).isEqualTo("Test Organisation");

        MetadataMap tx2Org = (MetadataMap) tx2Map.get("org");
        assertThat(tx2Org.get("id")).isEqualTo("org999");
        assertThat(tx2Org.get("name")).isEqualTo("Second Organisation");

        assertThat(hex(map)).isEqualTo(TRANSACTION_MULTI_BATCH_DIFFERENT_ORGS_EXPECTED_CBOR_HEX);
    }

    /**
     * A single transaction with two items, so the {@code items} CBOR array actually has more than one
     * element to pin. Uses {@code LinkedHashSet} with an explicit insertion order (not {@code
     * Set.of(...)}) for the same reason as the multi-transaction batch above.
     */
    @Test
    void transactionManifestCborIsUnchanged_multiItemTransaction() throws CborException {
        API1MetadataSerialiser serialiser = new API1MetadataSerialiser(FIXED);

        Set<TransactionEntity> transactions = new LinkedHashSet<>(List.of(transactionWithMultipleItemsFixture()));

        MetadataMap map = serialiser.serialiseToMetadataMap("org123", transactions, CREATION_SLOT);

        assertThat(hex(map)).isEqualTo(TRANSACTION_MULTI_ITEM_EXPECTED_CBOR_HEX);
    }

    private static org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation transactionOrganisationFixture() {
        var organisation = new org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation();
        organisation.setId("org123");
        organisation.setName("Test Organisation");
        organisation.setTaxIdNumber("123456789");
        organisation.setCurrencyId("USD");
        organisation.setCountryCode("US");
        return organisation;
    }

    private static TransactionEntity transactionFixture() {
        TransactionEntity transaction = new TransactionEntity();
        transaction.setId("tx123");
        transaction.setInternalNumber("1");
        transaction.setBatchId("batch1");
        transaction.setTransactionType(FxRevaluation);
        transaction.setEntryDate(LocalDate.of(2023, 2, 15));
        transaction.setAccountingPeriod(YearMonth.of(2023, 2));
        transaction.setOrganisation(transactionOrganisationFixture());

        TransactionItemEntity item = new TransactionItemEntity();
        item.setId("item1");
        item.setAmountFcy(new BigDecimal("100.00"));
        item.setAmountLcy(new BigDecimal("100.00"));
        item.setFxRate(new BigDecimal("1.0"));
        item.setDocument(Document.builder()
                .num("doc1")
                .currency(Currency.builder()
                        .customerCode("USD")
                        .build())
                .counterparty(Counterparty.builder()
                        .customerCode("CP 000001")
                        .type(VENDOR)
                        .build())
                .vat(Vat.builder()
                        .customerCode("CH-VH-3.8")
                        .rate(BigDecimal.valueOf(0.038))
                        .build())
                .build());
        item.setProject(Project.builder()
                .customerCode("AN 000001 2023")
                .name("Summit")
                .build());
        item.setCostCenter(CostCenter.builder()
                .customerCode("CC 000001")
                .name("Cost Center")
                .build());

        transaction.setItems(Set.of(item));
        return transaction;
    }

    private static TransactionEntity secondTransactionFixture() {
        TransactionEntity transaction = new TransactionEntity();
        transaction.setId("tx456");
        transaction.setInternalNumber("2");
        transaction.setBatchId("batch2");
        transaction.setTransactionType(Journal);
        transaction.setEntryDate(LocalDate.of(2023, 3, 20));
        transaction.setAccountingPeriod(YearMonth.of(2023, 3));
        transaction.setOrganisation(transactionOrganisationFixture());

        TransactionItemEntity item = new TransactionItemEntity();
        item.setId("item2");
        item.setAmountFcy(new BigDecimal("250.00"));
        item.setAmountLcy(new BigDecimal("250.00"));
        item.setFxRate(new BigDecimal("1.0"));
        item.setDocument(Document.builder()
                .num("doc2")
                .currency(Currency.builder()
                        .customerCode("EUR")
                        .build())
                .counterparty(Counterparty.builder()
                        .customerCode("CP 000002")
                        .type(VENDOR)
                        .build())
                .vat(Vat.builder()
                        .customerCode("CH-VH-7.7")
                        .rate(BigDecimal.valueOf(0.077))
                        .build())
                .build());
        item.setProject(Project.builder()
                .customerCode("AN 000002 2023")
                .name("Everest")
                .build());
        item.setCostCenter(CostCenter.builder()
                .customerCode("CC 000002")
                .name("Cost Center Two")
                .build());

        transaction.setItems(new LinkedHashSet<>(List.of(item)));
        return transaction;
    }

    private static org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation secondTransactionOrganisationFixture() {
        var organisation = new org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation();
        organisation.setId("org999");
        organisation.setName("Second Organisation");
        organisation.setTaxIdNumber("987654321");
        organisation.setCurrencyId("EUR");
        organisation.setCountryCode("DE");
        return organisation;
    }

    private static TransactionEntity thirdTransactionFixtureOtherOrganisation() {
        TransactionEntity transaction = new TransactionEntity();
        transaction.setId("tx789");
        transaction.setInternalNumber("3");
        transaction.setBatchId("batch3");
        transaction.setTransactionType(Journal);
        transaction.setEntryDate(LocalDate.of(2023, 5, 5));
        transaction.setAccountingPeriod(YearMonth.of(2023, 5));
        transaction.setOrganisation(secondTransactionOrganisationFixture());

        TransactionItemEntity item = new TransactionItemEntity();
        item.setId("item3");
        item.setAmountFcy(new BigDecimal("300.00"));
        item.setAmountLcy(new BigDecimal("300.00"));
        item.setFxRate(new BigDecimal("1.0"));
        item.setDocument(Document.builder()
                .num("doc3")
                .currency(Currency.builder()
                        .customerCode("EUR")
                        .build())
                .build());

        transaction.setItems(new LinkedHashSet<>(List.of(item)));
        return transaction;
    }

    private static TransactionEntity transactionWithMultipleItemsFixture() {
        TransactionEntity transaction = new TransactionEntity();
        transaction.setId("tx-multi-item");
        transaction.setInternalNumber("4");
        transaction.setBatchId("batch4");
        transaction.setTransactionType(FxRevaluation);
        transaction.setEntryDate(LocalDate.of(2023, 4, 10));
        transaction.setAccountingPeriod(YearMonth.of(2023, 4));
        transaction.setOrganisation(transactionOrganisationFixture());

        TransactionItemEntity itemA = new TransactionItemEntity();
        itemA.setId("item-a");
        itemA.setAmountFcy(new BigDecimal("50.00"));
        itemA.setAmountLcy(new BigDecimal("50.00"));
        itemA.setFxRate(new BigDecimal("1.0"));
        itemA.setDocument(Document.builder()
                .num("doc-a")
                .currency(Currency.builder()
                        .customerCode("USD")
                        .build())
                .counterparty(Counterparty.builder()
                        .customerCode("CP 000003")
                        .type(VENDOR)
                        .build())
                .build());
        itemA.setProject(Project.builder()
                .customerCode("AN 000003 2023")
                .name("K2")
                .build());

        TransactionItemEntity itemB = new TransactionItemEntity();
        itemB.setId("item-b");
        itemB.setAmountFcy(new BigDecimal("75.00"));
        itemB.setAmountLcy(new BigDecimal("75.00"));
        itemB.setFxRate(new BigDecimal("1.0"));
        itemB.setDocument(Document.builder()
                .num("doc-b")
                .currency(Currency.builder()
                        .customerCode("USD")
                        .build())
                .build());
        itemB.setCostCenter(CostCenter.builder()
                .customerCode("CC 000003")
                .name("Cost Center Three")
                .build());

        transaction.setItems(new LinkedHashSet<>(List.of(itemA, itemB)));
        return transaction;
    }

    // ---------------------------------------------------------------------------------------
    // SPENDING EVENT (SpendingEventMetadataSerialiser) — fixture copied from
    // SpendingEventMetadataSerialiserTest
    // ---------------------------------------------------------------------------------------

    @Test
    void spendingEventManifestCborIsUnchanged() throws CborException {
        SpendingEventMetadataSerialiser serialiser = new SpendingEventMetadataSerialiser(FIXED);

        MetadataMap map = serialiser.serialiseToMetadataMap(Set.of(spendingEventFixture()), CREATION_SLOT);

        assertThat(hex(map)).isEqualTo(SPENDING_EVENT_EXPECTED_CBOR_HEX);
    }

    /**
     * A two-event bundle: the first event keeps the original sub-project allocation shape, the
     * second uses a direct (non-sub-project) allocation, so both {@code allocation} shapes are pinned
     * together in the same array. Both events share the same organisation, as the serialiser requires
     * (see its Javadoc). Uses {@code LinkedHashSet} with an explicit insertion order (not {@code
     * Set.of(...)}) so which event lands at CBOR array index 0 vs. 1 is fixed — see class Javadoc.
     */
    @Test
    void spendingEventManifestCborIsUnchanged_multiEventBundle() throws CborException {
        SpendingEventMetadataSerialiser serialiser = new SpendingEventMetadataSerialiser(FIXED);

        Set<SpendingEventEntity> events = new LinkedHashSet<>(List.of(spendingEventFixture(), secondSpendingEventFixture()));

        MetadataMap map = serialiser.serialiseToMetadataMap(events, CREATION_SLOT);

        assertThat(hex(map)).isEqualTo(SPENDING_EVENT_MULTI_EVENT_EXPECTED_CBOR_HEX);
    }

    private static org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation spendingOrganisationFixture() {
        var organisation = new org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation();
        organisation.setId("org123");
        organisation.setName("Test Organisation");
        organisation.setTaxIdNumber("123456789");
        organisation.setCurrencyId("ISO_4217:CHF");
        organisation.setCountryCode("CH");
        return organisation;
    }

    /** A sub-project allocation: the milestones are published nested inside the sub_project object. */
    private static EventProjectAllocationEntity spendingProjectAllocationFixture() {
        EventMilestoneAllocationEntity milestone = EventMilestoneAllocationEntity.builder()
                .milestoneId("ms1")
                .milestoneTitle("Milestone AB")
                .allocatedAmount(new BigDecimal("85.00"))
                .build();

        return EventProjectAllocationEntity.builder()
                .projectId("ProjectID1")
                .projectTitle("ProjectTitle")
                .subProjectId("SubProjectID1")
                .subProjectTitle("SubProjectTitle")
                .milestones(List.of(milestone))
                .build();
    }

    private static SpendingEventEntity spendingEventFixture() {
        SpendingEventEntity event = new SpendingEventEntity();
        event.setEventId("event1");
        event.setEventType(EventType.SPENDING);
        event.setFundingId("fund1");
        event.setFundingTx("ftx1");
        event.setCurrencyRcy("USD");
        event.setCurrencyRcyId("ISO_4217:USD");
        event.setEventDate(LocalDate.of(2025, 4, 3));
        event.setCategory("Personnel");
        event.setVendor("Vendor AB");
        event.setAmountFcy(new BigDecimal("100.00"));
        event.setAmountRcy(new BigDecimal("85.00"));
        event.setCurrencyFcy("EUR");
        event.setCurrencyFcyId("ISO_4217:EUR");
        event.setFxRate(new BigDecimal("0.85"));
        event.setDocumentHash("doc-hash-1");
        event.setNotes("Invoice #1");
        event.setOrganisation(spendingOrganisationFixture());
        event.setProjectAllocations(List.of(spendingProjectAllocationFixture()));
        return event;
    }

    /** A direct (non-sub-project) allocation, pinning the shape the singleton fixture above omits. */
    private static EventProjectAllocationEntity secondSpendingProjectAllocationFixture() {
        EventMilestoneAllocationEntity milestone = EventMilestoneAllocationEntity.builder()
                .milestoneId("ms2")
                .milestoneTitle("Milestone CD")
                .allocatedAmount(new BigDecimal("170.00"))
                .build();

        return EventProjectAllocationEntity.builder()
                .projectId("ProjectID2")
                .projectTitle("ProjectTitle2")
                .milestones(List.of(milestone))
                .build();
    }

    private static SpendingEventEntity secondSpendingEventFixture() {
        SpendingEventEntity event = new SpendingEventEntity();
        event.setEventId("event2");
        event.setEventType(EventType.SPENDING);
        event.setFundingId("fund2");
        event.setFundingTx("ftx2");
        event.setCurrencyRcy("EUR");
        event.setCurrencyRcyId("ISO_4217:EUR");
        event.setEventDate(LocalDate.of(2025, 5, 12));
        event.setCategory("Travel");
        event.setVendor("Vendor CD");
        event.setAmountFcy(new BigDecimal("200.00"));
        event.setAmountRcy(new BigDecimal("170.00"));
        event.setCurrencyFcy("GBP");
        event.setCurrencyFcyId("ISO_4217:GBP");
        event.setFxRate(new BigDecimal("0.85"));
        event.setDocumentHash("doc-hash-2");
        event.setNotes("Invoice #2");
        event.setOrganisation(spendingOrganisationFixture());
        event.setProjectAllocations(List.of(secondSpendingProjectAllocationFixture()));
        return event;
    }

}
