package org.cardanofoundation.reeve.demoapplication.configs;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.reeve.demoapplication.convertors.AccountNumberConvertor;
import org.cardanofoundation.reeve.demoapplication.convertors.CostCenterConvertor;
import org.cardanofoundation.reeve.demoapplication.convertors.ProjectConvertor;
import org.cardanofoundation.reeve.demoapplication.convertors.VatConvertor;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.assistance.AccountingPeriodCalculator;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.client.NetSuiteClient;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.core.FieldType;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.core.FinancialPeriodSource;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.repository.CodesMappingRepository;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.repository.IngestionRepository;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.event_handle.NetSuiteEventHandler;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal.CodesMappingService;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal.ExtractionParametersFilteringService;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal.NetSuiteExtractionService;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal.NetSuiteParser;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal.NetSuiteReconcilationService;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal.PreprocessorService;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal.SystemExtractionParametersFactory;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal.TransactionConverter;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal.TransactionTypeMapper;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.zalando.problem.Problem;

import java.util.HashMap;
import java.util.function.Function;

import static org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.core.FieldType.CHART_OF_ACCOUNT;
import static org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.core.FieldType.COST_CENTER;
import static org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.core.FieldType.PROJECT;
import static org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.core.FieldType.VAT;

@Configuration
@Slf4j
public class NetsuiteConfig {

    private final static String NETSUITE_CONNECTOR_ID = "DUMMY_CONNECTOR_ID";

    @Bean
    public NetSuiteClient netSuiteClient(ObjectMapper objectMapper,
                                         @Qualifier("netsuiteRestClient") RestClient restClient,
                                         @Value("${lob.netsuite.client.url}") String url,
                                         @Value("${lob.netsuite.client.token-url}") String tokenUrl,
                                         @Value("${lob.netsuite.client.private-key-file-path}") String privateKeyFilePath,
                                         @Value("${lob.netsuite.client.client-id}") String clientId,
                                         @Value("${lob.netsuite.client.certificate-id}") String certificateId
    ) {
        log.info("Creating NetSuite client with url: {}", url);
        return new NetSuiteClient(objectMapper, restClient, url, tokenUrl, privateKeyFilePath, certificateId, clientId);
    }

    @Bean
    public NetSuiteParser netSuiteParser(ObjectMapper objectMapper) {
        return new NetSuiteParser(objectMapper);
    }

    @Bean
    public ExtractionParametersFilteringService extractionParametersFilteringService() {
        return new ExtractionParametersFilteringService();
    }

    @Bean
    public SystemExtractionParametersFactory systemExtractionParametersFactory(OrganisationPublicApiIF organisationPublicApiIF,
                                                                               AccountingPeriodCalculator accountPeriodService)
    {
        return new SystemExtractionParametersFactory(organisationPublicApiIF, accountPeriodService);
    }

    @Bean("netsuite_adapter.TransactionConverter")
    public TransactionConverter transactionConverter(Validator validator,
                                                     CodesMappingService codesMappingService,
                                                     PreprocessorService preprocessorService,
                                                     TransactionTypeMapper transactionTypeMapper,
                                                     @Value("${lob.netsuite.financial.period.source:IMPLICIT}") FinancialPeriodSource financialPeriodSource) {
        return new TransactionConverter(validator,
                codesMappingService,
                preprocessorService,
                transactionTypeMapper,
                NETSUITE_CONNECTOR_ID,
                financialPeriodSource
        );
    }

    @Bean
    public NetSuiteExtractionService netSuiteExtractionService(IngestionRepository ingestionRepository,
                                                               NetSuiteClient netSuiteClient,
                                                               TransactionConverter transactionConverter,
                                                               ApplicationEventPublisher eventPublisher,
                                                               SystemExtractionParametersFactory extractionParametersFactory,
                                                               ExtractionParametersFilteringService parametersFilteringService,
                                                               NetSuiteParser netSuiteParser,
                                                               @Value("${lob.events.netsuite.to.core.send.batch.size:100}") int sendBatchSize,
                                                               @Value("${lob.events.netsuite.to.core.netsuite.instance.debug.mode:true}") boolean isDebugMode
    ) {
        return new NetSuiteExtractionService(
                ingestionRepository,
                netSuiteClient,
                transactionConverter,
                eventPublisher,
                extractionParametersFactory,
                parametersFilteringService,
                netSuiteParser,
                sendBatchSize,
                NETSUITE_CONNECTOR_ID,
                isDebugMode
        );
    }

    @Bean
    public NetSuiteReconcilationService netsuiteReconcilationService(IngestionRepository ingestionRepository,
                                                                     NetSuiteClient netSuiteClient,
                                                                     TransactionConverter transactionConverter,
                                                                     ApplicationEventPublisher eventPublisher,
                                                                     ExtractionParametersFilteringService parametersFilteringService,
                                                                     NetSuiteParser netSuiteParser,
                                                                     @Value("${lob.events.netsuite.to.core.send.batch.size:100}") int sendBatchSize,
                                                                     @Value("${lob.events.netsuite.to.core.netsuite.instance.debug.mode:true}") boolean isDebugMode
    ) {
        return new NetSuiteReconcilationService(
                ingestionRepository,
                netSuiteClient,
                transactionConverter,
                parametersFilteringService,
                netSuiteParser,
                eventPublisher,
                sendBatchSize,
                NETSUITE_CONNECTOR_ID,
                isDebugMode
        );
    }

    @Bean
    @ConditionalOnProperty(value = "lob.netsuite.enabled", havingValue = "true", matchIfMissing = true)
    public NetSuiteEventHandler netSuiteEventHandler(NetSuiteExtractionService netSuiteExtractionService,
                                                     NetSuiteReconcilationService netSuiteReconcilationService) {
        return new NetSuiteEventHandler(netSuiteExtractionService, netSuiteReconcilationService);
    }

    @Bean
    public CodesMappingService codesMappingService(CodesMappingRepository codesMappingRepository) {
        return new CodesMappingService(codesMappingRepository);
    }

    @Bean
    public TransactionTypeMapper transactionTypeMapper() {
        return new TransactionTypeMapper();
    }

    @Bean
    public PreprocessorService preprocessorService() {
        HashMap<FieldType, Function<String, Either<Problem, String>>> fieldProcessors = new HashMap<FieldType, Function<String, Either<Problem, String>>>();
        fieldProcessors.put(COST_CENTER, new CostCenterConvertor());
        fieldProcessors.put(PROJECT, new ProjectConvertor());
        fieldProcessors.put(CHART_OF_ACCOUNT, new AccountNumberConvertor());
        fieldProcessors.put(VAT, new VatConvertor());

        return new PreprocessorService(fieldProcessors);
    }

}
