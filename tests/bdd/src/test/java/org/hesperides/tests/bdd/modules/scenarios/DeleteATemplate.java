package org.hesperides.tests.bdd.modules.scenarios;

import cucumber.api.java8.En;
import org.hesperides.tests.bdd.CucumberSpringBean;
import org.hesperides.tests.bdd.modules.contexts.ExistingTemplateContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.Assert.assertEquals;

public class DeleteATemplate extends CucumberSpringBean implements En {

    @Autowired
    private ExistingTemplateContext existingTemplateContext;

    public DeleteATemplate() {
        When("^deleting this template$", () -> {
            existingTemplateContext.deleteExistingTemplate();
        });

        Then("^the template is successfully deleted$", () -> {
            ResponseEntity<String> response = existingTemplateContext.failTryingToGetTemplate();
            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        });
    }

    /**
     * TODO Tester la tentative de suppression d'un template qui n'existe pas => 404
     */
}
