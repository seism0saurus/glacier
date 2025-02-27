package de.seism0saurus.glacier.webservice.dto;

import lombok.Data;

@Data
public class InstanceOperator {
    private String domain;
    private String operatorName;
    private String operatorStreetAndNumber;
    private String operatorZipcode;
    private String operatorCity;
    private String operatorCountry;
    private String operatorPhone;
    private String operatorMail;
    private String operatorWebsite;
}
