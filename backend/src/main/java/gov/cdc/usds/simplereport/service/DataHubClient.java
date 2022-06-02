package gov.cdc.usds.simplereport.service;

import feign.Param;
import gov.cdc.usds.simplereport.config.DataHubClientConfiguration;
import gov.cdc.usds.simplereport.service.model.reportstream.UploadResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;

@FeignClient(
    name = "datahub",
    url = "${datahub.url}",
    configuration = DataHubClientConfiguration.class)
public interface DataHubClient {

  @PostMapping(value = "/api/reports", consumes = "text/csv")
  UploadResponse uploadCSV(@Param("file") byte[] file);
}
