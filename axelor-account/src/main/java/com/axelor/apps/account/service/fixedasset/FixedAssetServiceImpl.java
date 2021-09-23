/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2021 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.account.service.fixedasset;

import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.AnalyticDistributionTemplate;
import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.FixedAssetCategory;
import com.axelor.apps.account.db.FixedAssetDerogatoryLine;
import com.axelor.apps.account.db.FixedAssetLine;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.repo.FixedAssetCategoryRepository;
import com.axelor.apps.account.db.repo.FixedAssetLineRepository;
import com.axelor.apps.account.db.repo.FixedAssetRepository;
import com.axelor.apps.account.exception.IExceptionMessage;
import com.axelor.apps.account.service.AnalyticFixedAssetService;
import com.axelor.apps.account.service.config.AccountConfigService;
import com.axelor.apps.account.service.fixedasset.factory.FixedAssetLineServiceFactory;
import com.axelor.apps.account.service.move.MoveLineService;
import com.axelor.apps.base.db.repo.SequenceRepository;
import com.axelor.apps.base.service.administration.SequenceService;
import com.axelor.apps.tool.date.DateTool;
import com.axelor.db.JPA;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.repo.TraceBackRepository;
import com.axelor.i18n.I18n;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FixedAssetServiceImpl implements FixedAssetService {

  private static final String ARG_FIXED_ASSET_NPE_MSG =
      "fixedAsset can not be null when calling this function";

  protected FixedAssetRepository fixedAssetRepo;

  protected FixedAssetLineMoveService fixedAssetLineMoveService;

  protected MoveLineService moveLineService;

  protected AccountConfigService accountConfigService;

  protected FixedAssetDerogatoryLineService fixedAssetDerogatoryLineService;

  protected AnalyticFixedAssetService analyticFixedAssetService;

  protected SequenceService sequenceService;

  protected FixedAssetLineService fixedAssetLineService;

  protected FixedAssetLineServiceFactory fixedAssetLineServiceFactory;
  private final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  protected static final int CALCULATION_SCALE = 20;
  protected static final int RETURNED_SCALE = 2;
  public static final String SUFFIX_SPLITTED_FIXED_ASSET = "-%s %.2f";

  @Inject
  public FixedAssetServiceImpl(
      FixedAssetRepository fixedAssetRepo,
      FixedAssetLineMoveService fixedAssetLineMoveService,
      FixedAssetLineComputationService fixedAssetLineComputationService,
      MoveLineService moveLineService,
      AccountConfigService accountConfigService,
      FixedAssetDerogatoryLineService fixedAssetDerogatoryLineService,
      AnalyticFixedAssetService analyticFixedAssetService,
      SequenceService sequenceService,
      FixedAssetLineService fixedAssetLineService,
      FixedAssetLineServiceFactory fixedAssetLineServiceFactory) {
    this.fixedAssetRepo = fixedAssetRepo;
    this.fixedAssetLineMoveService = fixedAssetLineMoveService;
    this.moveLineService = moveLineService;
    this.accountConfigService = accountConfigService;
    this.fixedAssetDerogatoryLineService = fixedAssetDerogatoryLineService;
    this.analyticFixedAssetService = analyticFixedAssetService;
    this.sequenceService = sequenceService;
    this.fixedAssetLineService = fixedAssetLineService;
    this.fixedAssetLineServiceFactory = fixedAssetLineServiceFactory;
  }

  @Override
  public FixedAsset generateAndComputeLines(FixedAsset fixedAsset) throws AxelorException {

    if (fixedAsset.getGrossValue().signum() > 0) {

      if (fixedAsset.getFixedAssetLineList() != null
          && !fixedAsset.getFixedAssetLineList().isEmpty()) {
        fixedAssetLineService.clear(fixedAsset.getFixedAssetLineList());
      }
      if (fixedAsset.getFiscalFixedAssetLineList() != null
          && !fixedAsset.getFiscalFixedAssetLineList().isEmpty()) {
        fixedAssetLineService.clear(fixedAsset.getFiscalFixedAssetLineList());
      }
      if (fixedAsset.getIfrsFixedAssetLineList() != null
          && !fixedAsset.getIfrsFixedAssetLineList().isEmpty()) {
        fixedAssetLineService.clear(fixedAsset.getIfrsFixedAssetLineList());
      }

      generateAndComputeFixedAssetLines(fixedAsset);
      generateAndComputeFiscalFixedAssetLines(fixedAsset);
      generateAndComputeFixedAssetDerogatoryLines(fixedAsset);
      generateAndComputeIfrsFixedAssetLines(fixedAsset);
    } else {
      if (fixedAsset.getFixedAssetLineList() != null) {
        fixedAssetLineService.clear(fixedAsset.getFixedAssetLineList());
      }
      if (fixedAsset.getFiscalFixedAssetLineList() != null) {
        fixedAssetLineService.clear(fixedAsset.getFiscalFixedAssetLineList());
      }
      if (fixedAsset.getIfrsFixedAssetLineList() != null) {
        fixedAssetLineService.clear(fixedAsset.getIfrsFixedAssetLineList());
      }
    }

    return fixedAsset;
  }

  /**
   * {@inheritDoc}
   *
   * @throws NullPointerException if fixedAsset is null
   */
  @Override
  public void generateAndComputeFixedAssetDerogatoryLines(FixedAsset fixedAsset) {
    Objects.requireNonNull(fixedAsset, ARG_FIXED_ASSET_NPE_MSG);
    if (fixedAsset
        .getDepreciationPlanSelect()
        .contains(FixedAssetRepository.DEPRECIATION_PLAN_DEROGATION)) {

      List<FixedAssetDerogatoryLine> fixedAssetDerogatoryLineList =
          fixedAssetDerogatoryLineService.computePlannedFixedAssetDerogatoryLineList(fixedAsset);
      if (fixedAssetDerogatoryLineList.size() != 0) {
        if (fixedAsset.getFixedAssetDerogatoryLineList() == null) {
          fixedAsset.setFixedAssetDerogatoryLineList(new ArrayList<>());
          fixedAsset.getFixedAssetDerogatoryLineList().addAll(fixedAssetDerogatoryLineList);
        } else {
          List<FixedAssetDerogatoryLine> linesToKeep =
              fixedAsset.getFixedAssetDerogatoryLineList().stream()
                  .filter(
                      line -> line.getStatusSelect() == FixedAssetLineRepository.STATUS_REALIZED)
                  .collect(Collectors.toList());
          fixedAssetDerogatoryLineService.clear(fixedAsset.getFixedAssetDerogatoryLineList());
          fixedAsset.getFixedAssetDerogatoryLineList().addAll(linesToKeep);
          fixedAsset.getFixedAssetDerogatoryLineList().addAll(fixedAssetDerogatoryLineList);
        }
        fixedAssetDerogatoryLineService.computeDerogatoryBalanceAmount(
            fixedAsset.getFixedAssetDerogatoryLineList());
      }
    }
  }

  /**
   * {@inheritDoc}
   *
   * @throws AxelorException
   * @throws NullPointerException if fixedAsset is null
   */
  @Override
  public void generateAndComputeIfrsFixedAssetLines(FixedAsset fixedAsset) throws AxelorException {
    Objects.requireNonNull(fixedAsset, ARG_FIXED_ASSET_NPE_MSG);
    if (fixedAsset
        .getDepreciationPlanSelect()
        .contains(FixedAssetRepository.DEPRECIATION_PLAN_IFRS)) {
      FixedAssetLineComputationService fixedAssetLineComputationService =
          fixedAssetLineServiceFactory.getFixedAssetComputationService(
              FixedAssetLineRepository.TYPE_SELECT_IFRS);
      FixedAssetLine initialFiscalFixedAssetLine =
          fixedAssetLineComputationService.computeInitialPlannedFixedAssetLine(fixedAsset);
      fixedAsset.addIfrsFixedAssetLineListItem(initialFiscalFixedAssetLine);

      generateComputedPlannedFixedAssetLines(
          fixedAsset,
          initialFiscalFixedAssetLine,
          fixedAsset.getIfrsFixedAssetLineList(),
          FixedAssetLineRepository.TYPE_SELECT_IFRS);
    }
  }

  /**
   * {@inheritDoc}
   *
   * @throws AxelorException
   * @throws NullPointerException if fixedAsset is null
   */
  @Override
  public void generateAndComputeFiscalFixedAssetLines(FixedAsset fixedAsset)
      throws AxelorException {
    Objects.requireNonNull(fixedAsset, ARG_FIXED_ASSET_NPE_MSG);
    if (fixedAsset
        .getDepreciationPlanSelect()
        .contains(FixedAssetRepository.DEPRECIATION_PLAN_FISCAL)) {
      FixedAssetLineComputationService fixedAssetLineComputationService =
          fixedAssetLineServiceFactory.getFixedAssetComputationService(
              FixedAssetLineRepository.TYPE_SELECT_FISCAL);
      FixedAssetLine initialFiscalFixedAssetLine =
          fixedAssetLineComputationService.computeInitialPlannedFixedAssetLine(fixedAsset);
      fixedAsset.addFiscalFixedAssetLineListItem(initialFiscalFixedAssetLine);

      generateComputedPlannedFixedAssetLines(
          fixedAsset,
          initialFiscalFixedAssetLine,
          fixedAsset.getFiscalFixedAssetLineList(),
          FixedAssetLineRepository.TYPE_SELECT_FISCAL);
    }
  }
  /**
   * {@inheritDoc}
   *
   * @throws AxelorException
   * @throws NullPointerException if fixedAsset is null
   */
  @Override
  public void generateAndComputeFixedAssetLines(FixedAsset fixedAsset) throws AxelorException {
    Objects.requireNonNull(fixedAsset, ARG_FIXED_ASSET_NPE_MSG);
    if (fixedAsset
        .getDepreciationPlanSelect()
        .contains(FixedAssetRepository.DEPRECIATION_PLAN_ECONOMIC)) {
      FixedAssetLineComputationService fixedAssetLineComputationService =
          fixedAssetLineServiceFactory.getFixedAssetComputationService(
              FixedAssetLineRepository.TYPE_SELECT_ECONOMIC);
      FixedAssetLine initialFixedAssetLine =
          fixedAssetLineComputationService.computeInitialPlannedFixedAssetLine(fixedAsset);
      fixedAsset.addFixedAssetLineListItem(initialFixedAssetLine);

      generateComputedPlannedFixedAssetLines(
          fixedAsset,
          initialFixedAssetLine,
          fixedAsset.getFixedAssetLineList(),
          FixedAssetLineRepository.TYPE_SELECT_ECONOMIC);
    }
  }

  private List<FixedAssetLine> generateComputedPlannedFixedAssetLines(
      FixedAsset fixedAsset,
      FixedAssetLine initialFixedAssetLine,
      List<FixedAssetLine> fixedAssetLineList,
      int typeSelect)
      throws AxelorException {

    // counter to avoid too many iterations in case of a current or future mistake
    int c = 0;
    final int MAX_ITERATION = 1000;
    FixedAssetLine fixedAssetLine = initialFixedAssetLine;
    FixedAssetLineComputationService fixedAssetLineComputationService =
        fixedAssetLineServiceFactory.getFixedAssetComputationService(typeSelect);
    while (c < MAX_ITERATION && fixedAssetLine.getAccountingValue().signum() != 0) {
      fixedAssetLine =
          fixedAssetLineComputationService.computePlannedFixedAssetLine(fixedAsset, fixedAssetLine);
      fixedAssetLine.setFixedAsset(fixedAsset);
      fixedAssetLineList.add(fixedAssetLine);
      c++;
    }

    return fixedAssetLineList;
  }

  @Override
  @Transactional(rollbackOn = {Exception.class})
  public List<FixedAsset> createFixedAssets(Invoice invoice) throws AxelorException {

    List<FixedAsset> fixedAssetList = new ArrayList<>();
    if (invoice == null || CollectionUtils.isEmpty(invoice.getInvoiceLineList())) {
      return fixedAssetList;
    }

    AccountConfig accountConfig = accountConfigService.getAccountConfig(invoice.getCompany());

    for (InvoiceLine invoiceLine : invoice.getInvoiceLineList()) {

      if (accountConfig.getFixedAssetCatReqOnInvoice()
          && invoiceLine.getFixedAssets()
          && invoiceLine.getFixedAssetCategory() == null) {
        throw new AxelorException(
            invoiceLine,
            TraceBackRepository.CATEGORY_MISSING_FIELD,
            I18n.get(IExceptionMessage.INVOICE_LINE_ERROR_FIXED_ASSET_CATEGORY),
            invoiceLine.getProductName());
      }

      if (!invoiceLine.getFixedAssets() || invoiceLine.getFixedAssetCategory() == null) {
        continue;
      }

      FixedAsset fixedAsset = new FixedAsset();
      fixedAsset.setFixedAssetCategory(invoiceLine.getFixedAssetCategory());
      if (fixedAsset.getFixedAssetCategory() != null) {
        fixedAsset.setDepreciationPlanSelect(
            fixedAsset.getFixedAssetCategory().getDepreciationPlanSelect());
      }
      if (fixedAsset.getFixedAssetCategory().getIsValidateFixedAsset()) {
        fixedAsset.setStatusSelect(FixedAssetRepository.STATUS_VALIDATED);
      } else {
        fixedAsset.setStatusSelect(FixedAssetRepository.STATUS_DRAFT);
      }
      fixedAsset.setAcquisitionDate(invoice.getInvoiceDate());
      fixedAsset.setFirstDepreciationDate(invoice.getInvoiceDate());
      fixedAsset.setFirstServiceDate(invoice.getInvoiceDate());
      fixedAsset.setReference(invoice.getInvoiceId());
      fixedAsset.setName(invoiceLine.getProductName() + " (" + invoiceLine.getQty() + ")");
      fixedAsset.setCompany(fixedAsset.getFixedAssetCategory().getCompany());
      fixedAsset.setJournal(fixedAsset.getFixedAssetCategory().getJournal());
      copyInfos(fixedAsset.getFixedAssetCategory(), fixedAsset);
      fixedAsset.setGrossValue(invoiceLine.getCompanyExTaxTotal());
      fixedAsset.setPartner(invoice.getPartner());
      fixedAsset.setPurchaseAccount(invoiceLine.getAccount());
      fixedAsset.setInvoiceLine(invoiceLine);

      this.generateAndComputeLines(fixedAsset);

      fixedAssetList.add(fixedAssetRepo.save(fixedAsset));
    }
    return fixedAssetList;
  }

  @Override
  @Transactional(rollbackOn = {Exception.class})
  public void disposal(
      LocalDate disposalDate,
      BigDecimal disposalAmount,
      FixedAsset fixedAsset,
      int transferredReason)
      throws AxelorException {

    Map<Integer, List<FixedAssetLine>> fixedAssetLineMap =
        fixedAsset.getFixedAssetLineList().stream()
            .collect(Collectors.groupingBy(FixedAssetLine::getStatusSelect));
    List<FixedAssetLine> previousPlannedLineList =
        fixedAssetLineMap.get(FixedAssetLineRepository.STATUS_PLANNED);
    List<FixedAssetLine> previousRealizedLineList =
        fixedAssetLineMap.get(FixedAssetLineRepository.STATUS_REALIZED);
    FixedAssetLine previousPlannedLine =
        previousPlannedLineList != null && !previousPlannedLineList.isEmpty()
            ? previousPlannedLineList.get(0)
            : null;
    FixedAssetLine previousRealizedLine =
        previousRealizedLineList != null && !previousRealizedLineList.isEmpty()
            ? previousRealizedLineList.get(previousRealizedLineList.size() - 1)
            : null;

    if (previousPlannedLine != null
        && disposalDate.isAfter(previousPlannedLine.getDepreciationDate())) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          I18n.get(IExceptionMessage.FIXED_ASSET_DISPOSAL_DATE_ERROR_2));
    }

    if (previousRealizedLine != null
        && disposalDate.isBefore(previousRealizedLine.getDepreciationDate())) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          I18n.get(IExceptionMessage.FIXED_ASSET_DISPOSAL_DATE_ERROR_1));
    }

    if (disposalAmount.compareTo(BigDecimal.ZERO) != 0) {

      FixedAssetLine depreciationFixedAssetLine =
          fixedAssetLineService.generateProrataDepreciationLine(
              fixedAsset, disposalDate, previousRealizedLine);
      fixedAssetLineMoveService.realize(depreciationFixedAssetLine, false, true);
      fixedAssetLineMoveService.generateDisposalMove(
          fixedAsset,
          depreciationFixedAssetLine,
          transferredReason,
          depreciationFixedAssetLine.getDepreciationDate());
    } else {
      if (disposalAmount.compareTo(fixedAsset.getResidualValue()) != 0) {
        return;
      }
    }
    List<FixedAssetLine> fixedAssetLineList =
        fixedAsset.getFixedAssetLineList().stream()
            .filter(
                fixedAssetLine ->
                    fixedAssetLine.getStatusSelect() == FixedAssetLineRepository.STATUS_PLANNED)
            .collect(Collectors.toList());
    for (FixedAssetLine fixedAssetLine : fixedAssetLineList) {
      fixedAsset.removeFixedAssetLineListItem(fixedAssetLine);
    }
    fixedAssetLineService.clear(fixedAssetLineList);

    setDisposalFields(fixedAsset, disposalDate, disposalAmount, transferredReason);
    fixedAssetRepo.save(fixedAsset);
  }

  private void setDisposalFields(
      FixedAsset fixedAsset,
      LocalDate disposalDate,
      BigDecimal disposalAmount,
      int transferredReason) {
    fixedAsset.setDisposalDate(disposalDate);
    fixedAsset.setDisposalValue(disposalAmount);
    fixedAsset.setTransferredReasonSelect(transferredReason);
    fixedAsset.setStatusSelect(FixedAssetRepository.STATUS_TRANSFERRED);
  }

  @Override
  public int computeTransferredReason(Integer disposalTypeSelect, Integer disposalQtySelect) {
    if (disposalTypeSelect == FixedAssetRepository.DISPOSABLE_TYPE_SELECT_CESSION
        && disposalQtySelect == FixedAssetRepository.DISPOSABLE_QTY_SELECT_PARTIAL) {
      return FixedAssetRepository.TRANSFERED_REASON_PARTIAL_CESSION;
    } else if (disposalTypeSelect == FixedAssetRepository.DISPOSABLE_TYPE_SELECT_CESSION) {
      return FixedAssetRepository.TRANSFERED_REASON_CESSION;
    }
    return FixedAssetRepository.TRANSFERED_REASON_SCRAPPING;
  }

  @Override
  @Transactional
  public void createAnalyticOnMoveLine(
      AnalyticDistributionTemplate analyticDistributionTemplate, MoveLine moveLine)
      throws AxelorException {
    if (analyticDistributionTemplate != null
        && moveLine.getAccount().getAnalyticDistributionAuthorized()) {
      moveLine.setAnalyticDistributionTemplate(analyticDistributionTemplate);
      moveLine = moveLineService.createAnalyticDistributionWithTemplate(moveLine);
    }
  }

  @Override
  public void updateAnalytic(FixedAsset fixedAsset) throws AxelorException {
    if (fixedAsset.getAnalyticDistributionTemplate() != null) {
      if (fixedAsset.getDisposalMove() != null) {
        for (MoveLine moveLine : fixedAsset.getDisposalMove().getMoveLineList()) {
          this.createAnalyticOnMoveLine(fixedAsset.getAnalyticDistributionTemplate(), moveLine);
        }
      }
      if (fixedAsset.getFixedAssetLineList() != null) {
        for (FixedAssetLine fixedAssetLine : fixedAsset.getFixedAssetLineList()) {
          if (fixedAssetLine.getDepreciationAccountMove() != null) {
            for (MoveLine moveLine :
                fixedAssetLine.getDepreciationAccountMove().getMoveLineList()) {
              this.createAnalyticOnMoveLine(fixedAsset.getAnalyticDistributionTemplate(), moveLine);
            }
          }
        }
      }
    }
  }

  /**
   * {@inheritDoc}
   *
   * @throws AxelorException
   * @throws NullPointerException if fixedAsset is null
   */
  @Override
  @Transactional(rollbackOn = {Exception.class})
  public void validate(FixedAsset fixedAsset) throws AxelorException {
    Objects.requireNonNull(fixedAsset, ARG_FIXED_ASSET_NPE_MSG);
    if (fixedAsset.getGrossValue().compareTo(BigDecimal.ZERO) > 0) {
      if (fixedAsset.getFixedAssetLineList() != null) {
        if (!fixedAsset.getFixedAssetLineList().isEmpty()) {
          fixedAssetLineService.clear(fixedAsset.getFixedAssetLineList());
        }
      }
      if (fixedAsset.getFiscalFixedAssetLineList() != null) {
        if (!fixedAsset.getFiscalFixedAssetLineList().isEmpty()) {
          fixedAssetLineService.clear(fixedAsset.getFiscalFixedAssetLineList());
        }
      }
      if (fixedAsset.getFixedAssetDerogatoryLineList() != null) {
        if (!fixedAsset.getFixedAssetDerogatoryLineList().isEmpty()) {
          fixedAssetDerogatoryLineService.clear(fixedAsset.getFixedAssetDerogatoryLineList());
        }
      }
      if (fixedAsset.getIfrsFixedAssetLineList() != null) {
        if (!fixedAsset.getIfrsFixedAssetLineList().isEmpty()) {
          fixedAssetLineService.clear(fixedAsset.getIfrsFixedAssetLineList());
        }
      }

      if (!fixedAsset
          .getDepreciationPlanSelect()
          .contains(FixedAssetRepository.DEPRECIATION_PLAN_NONE)) {
        fixedAsset = generateAndComputeLines(fixedAsset);
      }

      if (fixedAsset.getIsEqualToFiscalDepreciation()) {
        fixedAsset.setAccountingValue(fixedAsset.getGrossValue());
      } else {
        fixedAsset.setAccountingValue(
            fixedAsset.getGrossValue().subtract(fixedAsset.getResidualValue()));
      }
      fixedAsset.setFixedAssetSeq(this.generateSequence(fixedAsset));

    } else {
      fixedAssetLineService.clear(fixedAsset.getFixedAssetLineList());
      fixedAssetLineService.clear(fixedAsset.getIfrsFixedAssetLineList());
      fixedAssetLineService.clear(fixedAsset.getFiscalFixedAssetLineList());
      fixedAssetDerogatoryLineService.clear(fixedAsset.getFixedAssetDerogatoryLineList());
    }
    fixedAsset.setStatusSelect(FixedAssetRepository.STATUS_VALIDATED);
    fixedAssetRepo.save(fixedAsset);
  }

  private String generateSequence(FixedAsset fixedAsset) {
    String seq =
        sequenceService.getSequenceNumber(SequenceRepository.FIXED_ASSET, fixedAsset.getCompany());
    return seq;
  }

  @Override
  public int massValidation(List<Long> fixedAssetIds) throws AxelorException {
    int count = 0;
    for (Long id : fixedAssetIds) {
      FixedAsset fixedAsset = fixedAssetRepo.find(id);
      if (fixedAsset.getStatusSelect() == FixedAssetRepository.STATUS_DRAFT) {
        validate(fixedAsset);
        JPA.clear();
        count++;
      }
    }
    return count;
  }

  /**
   * If firstDepreciationDateInitSeelct if acquisition Date THEN : -If PeriodicityTypeSelect = 12
   * (Year) >> FirstDepreciationDate = au 31/12 of the year of fixedAsset.acquisitionDate -if
   * PeriodicityTypeSelect = 1 (Month) >> FirstDepreciationDate = last day of the month of
   * fixedAsset.acquisitionDate Else (== first service date) -If PeriodicityTypeSelect = 12 (Year)
   * >> FirstDepreciationDate = au 31/12 of the year of fixedAsset.firstServiceDate -if
   * PeriodicityTypeSelect = 1 (Month) >> FirstDepreciationDate = last day of the month of
   * fixedAsset.firstServiceDate
   */
  @Override
  public void computeFirstDepreciationDate(FixedAsset fixedAsset) {

    FixedAssetCategory fixedAssetCategory = fixedAsset.getFixedAssetCategory();
    if (fixedAssetCategory == null) {
      return;
    }
    Integer periodicityTypeSelect = fixedAsset.getPeriodicityTypeSelect();
    Integer firstDepreciationDateInitSelect =
        fixedAssetCategory.getFirstDepreciationDateInitSelect();
    if (fixedAssetCategory != null
        && periodicityTypeSelect != null
        && firstDepreciationDateInitSelect != null) {
      if (firstDepreciationDateInitSelect
          == FixedAssetCategoryRepository.REFERENCE_FIRST_DEPRECIATION_DATE_ACQUISITION) {
        fixedAsset.setFirstDepreciationDate(
            analyticFixedAssetService.computeFirstDepreciationDate(
                fixedAsset, fixedAsset.getAcquisitionDate()));
      } else {
        fixedAsset.setFirstDepreciationDate(
            analyticFixedAssetService.computeFirstDepreciationDate(
                fixedAsset, fixedAsset.getFirstServiceDate()));
      }
    }
  }

  @Override
  @Transactional
  public void updateDepreciation(FixedAsset fixedAsset) throws AxelorException {
    Objects.requireNonNull(fixedAsset);
    BigDecimal correctedAccountingValue = fixedAsset.getCorrectedAccountingValue();
    if (correctedAccountingValue != null
        && correctedAccountingValue.signum() != 0
        && fixedAsset
            .getDepreciationPlanSelect()
            .contains(FixedAssetRepository.DEPRECIATION_PLAN_ECONOMIC)) {
      List<FixedAssetLine> fixedAssetLineList = fixedAsset.getFixedAssetLineList();
      Optional<FixedAssetLine> optFixedAssetLine =
          fixedAssetLineService.findOldestFixedAssetLine(
              fixedAssetLineList, FixedAssetLineRepository.STATUS_PLANNED, 0);

      if (!optFixedAssetLine.isPresent()) {
        return;
      }
      // We can proceed the next part.
      // We remove all fixedAssetLine that are not realized.
      List<FixedAssetLine> linesToRemove =
          fixedAssetLineList.stream()
              .filter(
                  fixedAssetLine ->
                      fixedAssetLine.getStatusSelect() == FixedAssetLineRepository.STATUS_PLANNED
                          && !fixedAssetLine.equals(optFixedAssetLine.get()))
              .collect(Collectors.toList());

      fixedAssetLineList.removeIf(
          fixedAssetLine ->
              fixedAssetLine.getStatusSelect() == FixedAssetLineRepository.STATUS_PLANNED
                  && !fixedAssetLine.equals(optFixedAssetLine.get()));
      fixedAssetLineService.clear(linesToRemove);

      FixedAssetLine firstPlannedFixedAssetLine = optFixedAssetLine.get();
      firstPlannedFixedAssetLine.setCorrectedAccountingValue(correctedAccountingValue);
      firstPlannedFixedAssetLine.setImpairmentValue(
          firstPlannedFixedAssetLine
              .getAccountingValue()
              .subtract(firstPlannedFixedAssetLine.getCorrectedAccountingValue()));
      Optional<FixedAssetLine> previousLastRealizedFAL =
          fixedAssetLineService.findNewestFixedAssetLine(
              fixedAssetLineList, FixedAssetLineRepository.STATUS_REALIZED, 0);
      if (previousLastRealizedFAL.isPresent()) {
        firstPlannedFixedAssetLine.setCumulativeDepreciation(
            previousLastRealizedFAL
                .get()
                .getCumulativeDepreciation()
                .add(firstPlannedFixedAssetLine.getDepreciation())
                .add(firstPlannedFixedAssetLine.getImpairmentValue().abs()));
      } else {
        firstPlannedFixedAssetLine.setCumulativeDepreciation(
            BigDecimal.ZERO
                .add(firstPlannedFixedAssetLine.getDepreciation())
                .add(firstPlannedFixedAssetLine.getImpairmentValue().abs()));
      }

      // To recompute we will change value of fixed asset. And we must revert theses change at the
      // end.
      BigDecimal originalGrossValue = fixedAsset.getGrossValue();
      LocalDate originalFirstDepreciationDate = fixedAsset.getFirstDepreciationDate();
      LocalDate originalFirstServiceDate = fixedAsset.getFirstServiceDate();
      Integer originalNumberOfDepreciation = fixedAsset.getNumberOfDepreciation();
      fixedAsset.setGrossValue(correctedAccountingValue);
      fixedAsset.setFirstDepreciationDate(
          analyticFixedAssetService.computeFirstDepreciationDate(
              fixedAsset,
              DateTool.plusMonths(
                  firstPlannedFixedAssetLine.getDepreciationDate(),
                  fixedAsset.getPeriodicityInMonth())));
      fixedAsset.setFirstServiceDate(fixedAsset.getFirstDepreciationDate());
      if (fixedAsset
          .getComputationMethodSelect()
          .equals(FixedAssetRepository.COMPUTATION_METHOD_LINEAR)) {
        // In linear mode we udapte number of depreciation because the depreciation is computed
        // depending on number of depreciation.
        // So must not count lines that are already in the list
        // In degressive we do not update number of depreciation because it seems the engine need
        // the full size for
        // calculation the full size
        fixedAsset.setNumberOfDepreciation(
            fixedAsset.getNumberOfDepreciation() - fixedAssetLineList.size());
        if (fixedAsset.getNumberOfDepreciation() <= 0) {
          return;
        }
      }
      updateLines(fixedAsset, firstPlannedFixedAssetLine, fixedAssetLineList);
      fixedAsset.setGrossValue(originalGrossValue);
      fixedAsset.setFirstDepreciationDate(originalFirstDepreciationDate);
      fixedAsset.setFirstServiceDate(originalFirstServiceDate);
      fixedAsset.setNumberOfDepreciation(originalNumberOfDepreciation);
      fixedAssetRepo.save(fixedAsset);
    }
  }

  private void updateLines(
      FixedAsset fixedAsset,
      FixedAssetLine firstPlannedFixedAssetLine,
      List<FixedAssetLine> fixedAssetLineList)
      throws AxelorException {
    FixedAssetLine initialFixedAssetLine =
        fixedAssetLineServiceFactory
            .getFixedAssetComputationService(FixedAssetLineRepository.TYPE_SELECT_ECONOMIC)
            .computeInitialPlannedFixedAssetLine(fixedAsset);
    initialFixedAssetLine.setCumulativeDepreciation(
        initialFixedAssetLine
            .getCumulativeDepreciation()
            .add(firstPlannedFixedAssetLine.getCumulativeDepreciation()));
    fixedAsset.addFixedAssetLineListItem(initialFixedAssetLine);
    generateComputedPlannedFixedAssetLines(
        fixedAsset,
        initialFixedAssetLine,
        fixedAssetLineList,
        FixedAssetLineRepository.TYPE_SELECT_ECONOMIC);
    generateAndComputeFixedAssetDerogatoryLines(fixedAsset);
  }

  /**
   * {@inheritDoc}
   *
   * @return the new fixed asset created when splitting.
   * @throws NullPointerException if fixedAsset or disposalQty or splittingDate are null
   */
  @Override
  public FixedAsset splitFixedAsset(
      FixedAsset fixedAsset, BigDecimal disposalQty, LocalDate splittingDate, String comments)
      throws AxelorException {
    Objects.requireNonNull(fixedAsset, "fixAsset can not be null when calling this function");
    Objects.requireNonNull(disposalQty, "disposalQty can not be null when calling this function");
    Objects.requireNonNull(
        splittingDate, "disposalDate can not be null when calling this function");
    FixedAsset newFixedAsset = copyFixedAsset(fixedAsset, disposalQty);

    BigDecimal prorata =
        disposalQty.divide(fixedAsset.getQty(), RETURNED_SCALE, RoundingMode.HALF_UP);
    BigDecimal remainingProrata =
        BigDecimal.ONE.subtract(prorata).setScale(RETURNED_SCALE, RoundingMode.HALF_UP);

    multiplyLinesBy(newFixedAsset, prorata);
    multiplyLinesBy(fixedAsset, remainingProrata);
    multiplyFieldsToSplit(newFixedAsset, prorata);
    multiplyFieldsToSplit(fixedAsset, remainingProrata);
    fixedAsset.setQty(fixedAsset.getQty().subtract(disposalQty));
    newFixedAsset.setQty(disposalQty);
    newFixedAsset.setName(
        fixedAsset.getName()
            + String.format(
                SUFFIX_SPLITTED_FIXED_ASSET,
                I18n.get("Quantity"),
                disposalQty.setScale(RETURNED_SCALE)));
    fixedAsset.setName(
        fixedAsset.getName()
            + String.format(
                SUFFIX_SPLITTED_FIXED_ASSET,
                I18n.get("Quantity"),
                fixedAsset.getQty().setScale(RETURNED_SCALE)));
    String commentsToAdd =
        String.format(
            I18n.get(FixedAssetRepository.SPLIT_MESSAGE_COMMENT),
            disposalQty,
            splittingDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
    newFixedAsset.setComments(comments);
    newFixedAsset.setComments(
        String.format(
            "%s %s",
            newFixedAsset.getComments() == null ? "" : newFixedAsset.getComments(), commentsToAdd));
    newFixedAsset.setOriginSelect(FixedAssetRepository.ORIGINAL_SELECT_SCISSION);
    return newFixedAsset;
  }

  private void multiplyFieldsToSplit(FixedAsset fixedAsset, BigDecimal prorata) {

    if (fixedAsset.getGrossValue() != null) {
      fixedAsset.setGrossValue(
          prorata
              .multiply(fixedAsset.getGrossValue())
              .setScale(RETURNED_SCALE, RoundingMode.HALF_UP));
    }
    if (fixedAsset.getResidualValue() != null) {
      fixedAsset.setResidualValue(
          prorata
              .multiply(fixedAsset.getResidualValue())
              .setScale(RETURNED_SCALE, RoundingMode.HALF_UP));
    }
    if (fixedAsset.getAccountingValue() != null) {
      fixedAsset.setAccountingValue(
          prorata
              .multiply(fixedAsset.getAccountingValue())
              .setScale(RETURNED_SCALE, RoundingMode.HALF_UP));
    }
    if (fixedAsset.getCorrectedAccountingValue() != null) {
      fixedAsset.setCorrectedAccountingValue(
          prorata
              .multiply(fixedAsset.getCorrectedAccountingValue())
              .setScale(RETURNED_SCALE, RoundingMode.HALF_UP));
    }
  }

  private FixedAsset copyFixedAsset(FixedAsset fixedAsset, BigDecimal disposalQty) {
    FixedAsset newFixedAsset = fixedAssetRepo.copy(fixedAsset, true);
    // Adding this copy because copy does not copy list
    fixedAssetLineService.copyFixedAssetLineList(fixedAsset, newFixedAsset);
    fixedAssetDerogatoryLineService.copyFixedAssetDerogatoryLineList(fixedAsset, newFixedAsset);
    newFixedAsset.setStatusSelect(fixedAsset.getStatusSelect());
    if (newFixedAsset.getStatusSelect() > FixedAssetRepository.STATUS_DRAFT) {
      newFixedAsset.setFixedAssetSeq(generateSequence(newFixedAsset));
    }
    newFixedAsset.addAssociatedFixedAssetsSetItem(fixedAsset);
    fixedAsset.addAssociatedFixedAssetsSetItem(newFixedAsset);
    return newFixedAsset;
  }

  @Override
  public FixedAsset filterListsByStatus(FixedAsset fixedAsset, int status) {
    Objects.requireNonNull(fixedAsset);
    fixedAssetLineService.filterListByStatus(fixedAsset.getFixedAssetLineList(), status);
    fixedAssetLineService.filterListByStatus(fixedAsset.getFiscalFixedAssetLineList(), status);
    fixedAssetLineService.filterListByStatus(fixedAsset.getIfrsFixedAssetLineList(), status);
    fixedAssetDerogatoryLineService.filterListByStatus(
        fixedAsset.getFixedAssetDerogatoryLineList(), status);
    return fixedAsset;
  }

  @Override
  public FixedAsset cession(
      FixedAsset fixedAsset,
      LocalDate disposalDate,
      BigDecimal disposalAmount,
      int transferredReason,
      String comments)
      throws AxelorException {
    if (disposalDate.isBefore(fixedAsset.getFirstServiceDate())) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          I18n.get(IExceptionMessage.IMMO_FIXED_ASSET_CESSION_BEFORE_FIRST_SERVICE_DATE));
    }
    if (fixedAssetLineService.getExistingLineWithSameYear(
            fixedAsset, disposalDate, FixedAssetLineRepository.STATUS_REALIZED)
        != null) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          I18n.get(IExceptionMessage.FIXED_ASSET_DISPOSAL_DATE_YEAR_ALREADY_ACCOUNTED));
    }
    FixedAssetLine correspondingFixedAssetLine =
        fixedAssetLineService.computeCessionLine(fixedAsset, disposalDate);
    if (correspondingFixedAssetLine != null) {
      if (fixedAsset
          .getDepreciationPlanSelect()
          .contains(FixedAssetRepository.DEPRECIATION_PLAN_DEROGATION)) {
        generateDerogatoryCessionMove(fixedAsset);
      }
      fixedAssetLineMoveService.realize(correspondingFixedAssetLine, false, false);
    }
    fixedAssetLineMoveService.generateDisposalMove(
        fixedAsset, correspondingFixedAssetLine, transferredReason, disposalDate);
    setDisposalFields(fixedAsset, disposalDate, disposalAmount, transferredReason);
    fixedAsset.setComments(comments);
    return fixedAsset;
  }

  protected void generateDerogatoryCessionMove(FixedAsset fixedAsset) throws AxelorException {

    List<FixedAssetDerogatoryLine> fixedAssetDerogatoryLineList =
        fixedAsset.getFixedAssetDerogatoryLineList();
    fixedAssetDerogatoryLineList.sort(
        (line1, line2) -> line2.getDepreciationDate().compareTo(line1.getDepreciationDate()));
    FixedAssetDerogatoryLine lastRealizedDerogatoryLine =
        fixedAssetDerogatoryLineList.stream()
            .filter(line -> line.getStatusSelect() == FixedAssetLineRepository.STATUS_REALIZED)
            .findFirst()
            .orElse(null);
    fixedAssetDerogatoryLineList.sort(
        (line1, line2) -> line1.getDepreciationDate().compareTo(line2.getDepreciationDate()));
    FixedAssetDerogatoryLine firstPlannedDerogatoryLine =
        fixedAssetDerogatoryLineList.stream()
            .filter(line -> line.getStatusSelect() == FixedAssetLineRepository.STATUS_PLANNED)
            .findFirst()
            .orElse(null);
    if (firstPlannedDerogatoryLine == null) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_NO_VALUE,
          I18n.get(IExceptionMessage.IMMO_FIXED_ASSET_MISSING_DEROGATORY_LINE));
    }
    fixedAssetDerogatoryLineService.generateDerogatoryCessionMove(
        firstPlannedDerogatoryLine, lastRealizedDerogatoryLine);
    firstPlannedDerogatoryLine.setStatusSelect(FixedAssetLineRepository.STATUS_REALIZED);
  }

  @Override
  @Transactional(rollbackOn = {Exception.class})
  public FixedAsset computeDisposal(
      FixedAsset fixedAsset,
      LocalDate disposalDate,
      BigDecimal disposalQty,
      BigDecimal disposalAmount,
      int transferredReason,
      String comments)
      throws AxelorException {
    FixedAsset createdFixedAsset = null;
    if (transferredReason == FixedAssetRepository.TRANSFERED_REASON_PARTIAL_CESSION) {
      createdFixedAsset = splitFixedAsset(fixedAsset, disposalQty, disposalDate, comments);
      createdFixedAsset =
          cession(
              createdFixedAsset,
              disposalDate,
              disposalAmount,
              transferredReason,
              createdFixedAsset.getComments());
      filterListsByStatus(createdFixedAsset, FixedAssetLineRepository.STATUS_PLANNED);
    } else if (transferredReason == FixedAssetRepository.TRANSFERED_REASON_CESSION) {
      fixedAsset = cession(fixedAsset, disposalDate, disposalAmount, transferredReason, comments);
      filterListsByStatus(fixedAsset, FixedAssetLineRepository.STATUS_PLANNED);
    } else {
      disposal(disposalDate, disposalAmount, fixedAsset, transferredReason);
    }
    fixedAssetRepo.save(fixedAsset);
    if (createdFixedAsset != null) {
      return fixedAssetRepo.save(createdFixedAsset);
    }
    return null;
  }

  @Override
  @Transactional
  public FixedAsset splitAndSaveFixedAsset(
      FixedAsset fixedAsset, BigDecimal disposalQty, LocalDate splittingDate, String comments)
      throws AxelorException {
    FixedAsset splittedFixedAsset =
        this.splitFixedAsset(fixedAsset, disposalQty, splittingDate, comments);
    fixedAssetRepo.save(fixedAsset);
    return fixedAssetRepo.save(splittedFixedAsset);
  }

  @Override
  public void multiplyLinesBy(FixedAsset fixedAsset, BigDecimal prorata) throws AxelorException {

    List<FixedAssetLine> fixedAssetLineList = fixedAsset.getFixedAssetLineList();
    List<FixedAssetLine> fiscalAssetLineList = fixedAsset.getFiscalFixedAssetLineList();
    List<FixedAssetLine> ifrsAssetLineList = fixedAsset.getIfrsFixedAssetLineList();
    List<FixedAssetDerogatoryLine> fixedAssetDerogatoryLineList =
        fixedAsset.getFixedAssetDerogatoryLineList();
    if (fixedAssetLineList != null) {
      fixedAssetLineServiceFactory
          .getFixedAssetComputationService(FixedAssetLineRepository.TYPE_SELECT_ECONOMIC)
          .multiplyLinesBy(fixedAssetLineList, prorata);
    }
    if (fiscalAssetLineList != null) {
      fixedAssetLineServiceFactory
          .getFixedAssetComputationService(FixedAssetLineRepository.TYPE_SELECT_FISCAL)
          .multiplyLinesBy(fiscalAssetLineList, prorata);
    }
    if (ifrsAssetLineList != null) {
      fixedAssetLineServiceFactory
          .getFixedAssetComputationService(FixedAssetLineRepository.TYPE_SELECT_IFRS)
          .multiplyLinesBy(ifrsAssetLineList, prorata);
    }
    if (fixedAsset
        .getDepreciationPlanSelect()
        .contains(FixedAssetRepository.DEPRECIATION_PLAN_DEROGATION)) {
      if (fixedAssetDerogatoryLineList != null) {
        fixedAssetDerogatoryLineService.multiplyLinesBy(fixedAssetDerogatoryLineList, prorata);
      }
    }
  }

  /**
   * {@inheritDoc}
   *
   * @throws NullPointerException if moveLine is null
   */
  @Override
  public FixedAsset generateFixedAsset(Move move, MoveLine moveLine) throws AxelorException {
    log.debug("Starting generation of fixed asset for move line :" + moveLine);
    Objects.requireNonNull(moveLine);

    FixedAsset fixedAsset = new FixedAsset();
    fixedAsset.setStatusSelect(FixedAssetRepository.STATUS_DRAFT);
    if (moveLine.getDescription() == null) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_MISSING_FIELD,
          I18n.get(IExceptionMessage.MOVE_LINE_GENERATION_FIXED_ASSET_MISSING_DESCRIPTION),
          moveLine.getName());
    }
    fixedAsset.setName(moveLine.getDescription());
    if (moveLine.getMove() != null) {
      fixedAsset.setCompany(moveLine.getMove().getCompany());
      fixedAsset.setJournal(moveLine.getMove().getJournal());
    }
    FixedAssetCategory fixedAssetCategory = moveLine.getFixedAssetCategory();
    fixedAsset.setFixedAssetCategory(fixedAssetCategory);
    fixedAsset.setPartner(moveLine.getPartner());
    fixedAsset.setPurchaseAccount(moveLine.getAccount());
    if (fixedAssetCategory != null) {
      copyInfos(fixedAssetCategory, fixedAsset);
    }
    fixedAsset.setGrossValue(moveLine.getDebit());
    LocalDate acquisitionDate =
        moveLine.getOriginDate() != null ? moveLine.getOriginDate() : moveLine.getDate();
    fixedAsset.setAcquisitionDate(acquisitionDate);
    fixedAsset.setPurchaseAccountMove(move);
    log.debug("Generated fixed asset : " + fixedAsset);
    return fixedAsset;
  }

  @Transactional
  @Override
  public FixedAsset generateAndSaveFixedAsset(Move move, MoveLine moveLine) throws AxelorException {

    return fixedAssetRepo.save(generateFixedAsset(move, moveLine));
  }

  @Override
  public void copyInfos(FixedAssetCategory fixedAssetCategory, FixedAsset fixedAsset) {
    fixedAsset.setAnalyticDistributionTemplate(
        fixedAssetCategory.getAnalyticDistributionTemplate());

    fixedAsset.setDepreciationPlanSelect(fixedAssetCategory.getDepreciationPlanSelect());
    String computationMethodSelect = fixedAssetCategory.getComputationMethodSelect();
    Integer numberOfDepreciation = fixedAssetCategory.getNumberOfDepreciation();
    Integer periodicityInMonth = fixedAssetCategory.getPeriodicityInMonth();
    Integer periodicityTypeSelect = fixedAssetCategory.getPeriodicityTypeSelect();
    Integer durationInMonth = fixedAssetCategory.getDurationInMonth();
    BigDecimal degressiveCoef = fixedAssetCategory.getDegressiveCoef();

    fixedAsset.setComputationMethodSelect(computationMethodSelect);
    fixedAsset.setIfrsComputationMethodSelect(computationMethodSelect);
    fixedAsset.setFiscalComputationMethodSelect(computationMethodSelect);

    fixedAsset.setNumberOfDepreciation(numberOfDepreciation);
    fixedAsset.setFiscalNumberOfDepreciation(numberOfDepreciation);
    fixedAsset.setIfrsNumberOfDepreciation(numberOfDepreciation);

    fixedAsset.setPeriodicityInMonth(periodicityInMonth);
    fixedAsset.setFiscalPeriodicityInMonth(periodicityInMonth);
    fixedAsset.setIfrsPeriodicityInMonth(periodicityInMonth);

    fixedAsset.setPeriodicityTypeSelect(periodicityTypeSelect);
    fixedAsset.setFiscalPeriodicityTypeSelect(periodicityTypeSelect);
    fixedAsset.setIfrsPeriodicityTypeSelect(periodicityTypeSelect);

    fixedAsset.setDurationInMonth(durationInMonth);
    fixedAsset.setFiscalDurationInMonth(durationInMonth);
    fixedAsset.setIfrsDurationInMonth(durationInMonth);

    fixedAsset.setDegressiveCoef(degressiveCoef);
    fixedAsset.setFiscalDegressiveCoef(degressiveCoef);
    fixedAsset.setIfrsDegressiveCoef(degressiveCoef);

    fixedAsset.setIsEqualToFiscalDepreciation(true);
    fixedAsset.setIsIfrsEqualToFiscalDepreciation(true);
  }
}
