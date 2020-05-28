package org.grobid.service.configuration;

import io.dropwizard.Configuration;

public class SoftwareServiceConfiguration extends Configuration {

    private String grobidHome;

    public String getGrobidHome() {
        return grobidHome;
    }

    public void setGrobidHome(String grobidHome) {
        this.grobidHome = grobidHome;
    }
}
