/*
 * Copyright 2019 Red Hat
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.apicurio.registry;

import io.apicurio.registry.ccompat.rest.ContentTypes;
import io.apicurio.registry.rest.beans.UpdateState;
import io.apicurio.registry.types.ArtifactState;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ValidatableResponse;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.anything;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that the REST API exposed at endpoint "/ccompat" follows the
 * <a href="https://docs.confluent.io/5.5.0/schema-registry/develop/api.html">Confluent API specification</a>,
 * unless otherwise stated.
 *
 * @author Jakub Senko <jsenko@redhat.com>
 */
@QuarkusTest
public class ConfluentCompatApiTest extends AbstractResourceTestBase {


    private static final String SCHEMA_SIMPLE = "{\"type\": \"string\"}";

    private static final String SCHEMA_SIMPLE_WRAPPED = "{\"schema\":\"{\\\"type\\\": \\\"string\\\"}\"}";

    private static final String SCHEMA_INVALID_WRAPPED = "{\"schema\":\"{\\\"type\\\": \\\"bloop\\\"}\"}";

    private static final String SCHEMA_1_WRAPPED = "{\"schema\": \"{\\\"type\\\": \\\"record\\\", \\\"name\\\": \\\"test1\\\", " +
            "\\\"fields\\\": [ {\\\"type\\\": \\\"string\\\", \\\"name\\\": \\\"field1\\\"} ] }\"}\"";

    private static final String SCHEMA_2_WRAPPED = "{\"schema\": \"{\\\"type\\\": \\\"record\\\", \\\"name\\\": \\\"test1\\\", " +
            "\\\"fields\\\": [ {\\\"type\\\": \\\"string\\\", \\\"name\\\": \\\"field1\\\"}, " +
            "{\\\"type\\\": \\\"string\\\", \\\"name\\\": \\\"field2\\\"} ] }\"}\"";
    
    private static final String CONFIG_BACKWARD = "{\"compatibility\": \"BACKWARD\"}";

    /**
     * Endpoint: /subjects/(string: subject)/versions
     */
    @Test
    public void testCreateSubject() {
        final String SUBJECT = "subject1";
        // POST
        ValidatableResponse res = given()
            .when()
                .contentType(ContentTypes.COMPAT_SCHEMA_REGISTRY_STABLE_LATEST)
                .body(SCHEMA_SIMPLE_WRAPPED)
                .post("/ccompat/subjects/{subject}/versions", SUBJECT)
            .then()
                .statusCode(200)
                .body("id", Matchers.allOf(Matchers.isA(Integer.class), Matchers.greaterThanOrEqualTo(0)));
        /*int id = */res.extract().jsonPath().getInt("id");
        // Verify
        given()
            .when()
                .get("/artifacts/{artifactId}", SUBJECT)
            .then()
                .statusCode(200)
                .body("", equalTo(new JsonPath(SCHEMA_SIMPLE).getMap("")));
        // Invalid
        given()
            .when()
                .contentType(ContentTypes.COMPAT_SCHEMA_REGISTRY_STABLE_LATEST)
                .body(SCHEMA_INVALID_WRAPPED)
                .post("/ccompat/subjects/{subject}/versions", SUBJECT)
            .then()
                .statusCode(422);
    }

    /**
     * Endpoint: /compatibility/subjects/{subject}/versions/{version}
     */
    @Test
    public void testCompatibilityCheck() {
        final String SUBJECT = "subject2";
        // Prepare
        given()
            .when()
                .contentType(ContentTypes.COMPAT_SCHEMA_REGISTRY_STABLE_LATEST)
                .body(SCHEMA_2_WRAPPED)
                .post("/ccompat/subjects/{subject}/versions", SUBJECT)
            .then()
                .statusCode(200)
                .body(anything());
        given()
            .when()
                .contentType(ContentTypes.COMPAT_SCHEMA_REGISTRY_STABLE_LATEST)
                .body(CONFIG_BACKWARD)
                .put("/ccompat/config/{subject}", SUBJECT)
                .then()
                .statusCode(200)
                .body(anything());
        // POST
        given()
            .when()
                .contentType(ContentTypes.COMPAT_SCHEMA_REGISTRY_STABLE_LATEST)
                .body(SCHEMA_1_WRAPPED)
                .post("/ccompat/compatibility/subjects/{subject}/versions/{version}", SUBJECT, "latest")
            .then()
                .statusCode(200)
                .body("is_compatible", equalTo(true));
        given()
            .when()
                .contentType(ContentTypes.COMPAT_SCHEMA_REGISTRY_STABLE_LATEST)
                .body(SCHEMA_SIMPLE_WRAPPED)
                .post("/ccompat/compatibility/subjects/{subject}/versions/{version}", SUBJECT, "latest")
            .then()
                .statusCode(200)
                .body("is_compatible", equalTo(false));
    }
    
    @Test
    public void testDisabledStateCheck() {
        final String SUBJECT = "subject3";
        // Prepare
        given()
            .when()
                .contentType(ContentTypes.COMPAT_SCHEMA_REGISTRY_STABLE_LATEST)
                .body(SCHEMA_SIMPLE_WRAPPED)
                .post("/ccompat/subjects/{subject}/versions", SUBJECT)
            .then()
                .statusCode(200)
                .body("id", Matchers.allOf(Matchers.isA(Integer.class), Matchers.greaterThanOrEqualTo(0)));
        //verify
        given()
            .when()
                .get("/artifacts/{artifactId}", SUBJECT)
            .then()
                .statusCode(200)
                .body("", equalTo(new JsonPath(SCHEMA_SIMPLE).getMap("")));
        
        //Update state
        UpdateState updateState = new UpdateState();
        updateState.setState(ArtifactState.DISABLED);
        given()
           .when()
               .contentType(ContentTypes.JSON)
               .body(updateState)
               .put("/artifacts/{artifactId}/state", SUBJECT)
           .then()
               .statusCode(204);
        
        // GET - shouldn't return as the state has been changed to DISABLED
        given()
            .when()
                .contentType(ContentTypes.COMPAT_SCHEMA_REGISTRY_STABLE_LATEST)
                .get("/ccompat/subjects/{subject}/versions/{version}", SUBJECT, "latest")
            .then()
                .statusCode(400);
    }
    
    @Test
    public void testDeletedStateCheck() {
        final String SUBJECT = "subject4";
        // Prepare
        given()
            .when()
                .contentType(ContentTypes.COMPAT_SCHEMA_REGISTRY_STABLE_LATEST)
                .body(SCHEMA_SIMPLE_WRAPPED)
                .post("/ccompat/subjects/{subject}/versions", SUBJECT)
            .then().log().all()
                .statusCode(200)
                .body("id", Matchers.allOf(Matchers.isA(Integer.class), Matchers.greaterThanOrEqualTo(0)));
        //verify
        given()
            .when()
                .get("/artifacts/{artifactId}", SUBJECT)
            .then()
                .statusCode(200)
                .body("", equalTo(new JsonPath(SCHEMA_SIMPLE).getMap("")));
        
        //Update state
        UpdateState us = new UpdateState();
        us.setState(ArtifactState.DELETED);
        given()
           .when()
               .contentType(ContentTypes.JSON)
               .body(us)
               .put("/artifacts/{artifactId}/state", SUBJECT)
           .then()
               .statusCode(204);
        
        // GET - shouldn't return as the state has been changed to DELETED
        given()
            .when()
                .contentType(ContentTypes.COMPAT_SCHEMA_REGISTRY_STABLE_LATEST)
                .get("/ccompat/subjects/{subject}/versions/{version}", SUBJECT, "latest")
            .then()
            .statusCode(404); 
    }

    @Test
    public void testSchemaTypes() {
        //verify
        String[] types = given()
                .when()
                .get("/ccompat/schemas/types")
                .then()
                .statusCode(200)
        .extract().as(String[].class);

        assertEquals(3, types.length);
        assertEquals("JSON", types[0]);
        assertEquals("PROTOBUF", types[1]);
        assertEquals("AVRO", types[2]);
    }

    /**
     * Endpoint: /schemas/ids/{int: id}/versions
     */
    @Test
    public void testGetSchemaVersions() {
        final String SUBJECT = "subjectTestSchemaVersions";

        //Create two versions of the same artifact
        // POST
        given()
                .when()
                .contentType(ContentTypes.COMPAT_SCHEMA_REGISTRY_STABLE_LATEST)
                .body(SCHEMA_SIMPLE_WRAPPED)
                .post("/ccompat/subjects/{subject}/versions", SUBJECT)
                .then()
                .statusCode(200)
                .body("id", Matchers.allOf(Matchers.isA(Integer.class), Matchers.greaterThanOrEqualTo(0)));

        // POST
        given()
                .when()
                .contentType(ContentTypes.COMPAT_SCHEMA_REGISTRY_STABLE_LATEST)
                .body(SCHEMA_SIMPLE_WRAPPED)
                .post("/ccompat/subjects/{subject}/versions", SUBJECT)
                .then()
                .statusCode(200)
                .body("id", Matchers.allOf(Matchers.isA(Integer.class), Matchers.greaterThanOrEqualTo(1)));


        final Integer globalId = given().when().get("/ccompat/subjects/{subject}/versions/latest", SUBJECT).body().jsonPath().get("id");

        //Verify
        Assertions.assertEquals(Arrays.asList(1, 2), given()
                .when()
                .contentType(ContentTypes.COMPAT_SCHEMA_REGISTRY_STABLE_LATEST)
                .get("/ccompat/schemas/ids/{id}/versions", globalId)
                .then()
                .extract().body().jsonPath().get("version"));
    }

    /**
     * Endpoint: /ccompat/subjects/{subject}/versions/{version}/referencedby
     */
    @Test
    public void testGetSchemaReferencedVersions() {
        final String SUBJECT = "testGetSchemaReferencedVersions";

        //Create two versions of the same artifact
        // POST
        given().when().contentType(ContentTypes.COMPAT_SCHEMA_REGISTRY_STABLE_LATEST)
                .body(SCHEMA_SIMPLE_WRAPPED).post("/ccompat/subjects/{subject}/versions", SUBJECT).then()
                .statusCode(200)
                .body("id", Matchers.allOf(Matchers.isA(Integer.class), Matchers.greaterThanOrEqualTo(0)));

        // POST
        given().when().contentType(ContentTypes.COMPAT_SCHEMA_REGISTRY_STABLE_LATEST)
                .body(SCHEMA_SIMPLE_WRAPPED).post("/ccompat/subjects/{subject}/versions", SUBJECT).then()
                .statusCode(200)
                .body("id", Matchers.allOf(Matchers.isA(Integer.class), Matchers.greaterThanOrEqualTo(1)));

        //Verify
        Integer[] versions = given().when()
                .get("/ccompat/subjects/{subject}/versions/{version}/referencedby", SUBJECT, 1L).then().statusCode(200)
                .extract().as(Integer[].class);

        assertEquals(2, versions.length);
    }
}
