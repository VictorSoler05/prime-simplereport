import { useEffect, useRef, useState } from "react";
import Modal from "react-modal";
import { faDownload } from "@fortawesome/free-solid-svg-icons";
import { FontAwesomeIcon } from "@fortawesome/react-fontawesome";
import { CSVLink } from "react-csv";
import { ApolloError } from "@apollo/client";

import { showError } from "../../utils/srToast";
import Button from "../../commonComponents/Button/Button";
import {
  GetResultsForDownloadQuery,
  useGetResultsForDownloadLazyQuery,
} from "../../../generated/graphql";
import { parseDataForCSV, ResultCsvRow } from "../../utils/testResultCSV";
import { useDisabledFeatureDiseaseList } from "../../utils/disease";

import { ALL_FACILITIES_ID, ResultsQueryVariables } from "./TestResultsList";

interface DownloadResultsCsvModalProps {
  filterParams: FilterParams;
  modalIsOpen: boolean;
  closeModal: () => void;
  totalEntries: number;
  activeFacilityId: string;
}

export const DownloadResultsCsvModal = ({
  filterParams,
  modalIsOpen,
  closeModal,
  totalEntries,
  activeFacilityId,
}: DownloadResultsCsvModalProps) => {
  const rowsMaxLimit = 20000;
  const [results, setResults] = useState<ResultCsvRow[]>([]);
  const csvLink = useRef<
    CSVLink & HTMLAnchorElement & { link: HTMLAnchorElement }
  >(null);
  // Disable downloads because backend will hang on over 20k results (#3953)
  const disableDownload = totalEntries > rowsMaxLimit;
  const disabledFeatureDiseaseList = useDisabledFeatureDiseaseList();

  const filtersPresent = Object.entries(filterParams).some(([key, val]) => {
    // active facility in the facility filter is the default
    if (key === "filterFacilityId") {
      return val !== activeFacilityId;
    }
    return val;
  });

  const variables: ResultsQueryVariables = {
    facilityId:
      filterParams.filterFacilityId === ALL_FACILITIES_ID
        ? null
        : filterParams.filterFacilityId ?? activeFacilityId,
    pageNumber: 0,
    pageSize: totalEntries,
    ...filterParams,
  };

  const [downloadTestResultsQuery, { loading }] =
    useGetResultsForDownloadLazyQuery({
      variables,
      fetchPolicy: "no-cache",
      onCompleted: (data: GetResultsForDownloadQuery) => handleComplete(data),
      onError: (error: ApolloError) => handleError(error),
    });

  const handleComplete = (data: GetResultsForDownloadQuery) => {
    if (data?.resultsPage?.content) {
      try {
        const csvResults = parseDataForCSV(
          data.resultsPage.content,
          disabledFeatureDiseaseList
        );
        setResults(csvResults);
      } catch {
        showError("Error creating results file to download");
      }
    } else {
      showError("Unknown error downloading results");
    }
  };

  const handleError = (error: ApolloError) => {
    showError("Error downloading results", error.message);
  };

  // triggers the download of the file only after the csv data has been properly set
  useEffect(() => {
    csvLink?.current?.link.click();
    closeModal();
  }, [results]); // eslint-disable-line react-hooks/exhaustive-deps

  const pluralizeRows = (entriesCount: number) => {
    return entriesCount > 1 ? "s" : "";
  };

  return (
    <Modal
      isOpen={modalIsOpen}
      style={{
        content: {
          maxHeight: "90vh",
          width: "40em",
          position: "initial",
        },
      }}
      overlayClassName="prime-modal-overlay display-flex flex-align-center flex-justify-center"
      contentLabel="Download test results"
      ariaHideApp={process.env.NODE_ENV !== "test"}
      onRequestClose={closeModal}
    >
      <div className="border-0 card-container">
        <div className="display-flex flex-justify">
          <h1 className="font-heading-lg margin-top-05 margin-bottom-0">
            {disableDownload
              ? "Too many results selected"
              : "Download test results"}
          </h1>
          <button
            onClick={closeModal}
            className="close-button"
            aria-label="Close"
          >
            <span className="fa-layers">
              <FontAwesomeIcon icon={"circle"} size="2x" inverse />
              <FontAwesomeIcon icon={"times-circle"} size="2x" />
            </span>
          </button>
        </div>
        <div className="border-top border-base-lighter margin-x-neg-205 margin-top-205"></div>
        {disableDownload ? (
          <div className="grid-row grid-gap">
            <p>
              Please filter test results and download again with 20,000 results
              or fewer.
            </p>
          </div>
        ) : (
          <>
            <div className="grid-row grid-gap">
              <p>
                {filtersPresent
                  ? "Download results with current search filters applied?"
                  : "Download results without any search filters applied?"}
              </p>
            </div>
            <div className="grid-row grid-gap">
              <p>
                The CSV file will include {totalEntries} row
                {pluralizeRows(totalEntries)}.
              </p>
            </div>
          </>
        )}
        <div className="border-top border-base-lighter margin-x-neg-205 margin-top-2 padding-top-205 text-right">
          <div className="display-flex flex-justify-end">
            <Button
              className="margin-right-2"
              onClick={closeModal}
              variant="unstyled"
              label={disableDownload ? "Go back" : "No, go back"}
            />
            <Button
              onClick={() => downloadTestResultsQuery()}
              disabled={disableDownload}
              icon={faDownload}
              label={loading ? "Loading..." : "Download results"}
            />
            <CSVLink
              data={results}
              filename="simplereport-test-results.csv"
              className="hidden"
              ref={csvLink}
              target="_blank"
              rel="noopener noreferrer"
              tabIndex={-1}
              aria-hidden={true}
            />
          </div>
        </div>
      </div>
    </Modal>
  );
};

export default DownloadResultsCsvModal;
