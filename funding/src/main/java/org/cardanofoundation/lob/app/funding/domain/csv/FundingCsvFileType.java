package org.cardanofoundation.lob.app.funding.domain.csv;

/** The two CSV file kinds the bulk importer accepts, processed in this order for referential integrity. */
public enum FundingCsvFileType {

    PROJECTS_MILESTONES(ProjectMilestoneCsvLine.class),
    EVENTS(EventCsvLine.class);

    private final Class<?> lineType;

    FundingCsvFileType(Class<?> lineType) {
        this.lineType = lineType;
    }

    public Class<?> getLineType() {
        return lineType;
    }
}
