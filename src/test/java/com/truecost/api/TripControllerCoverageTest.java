package com.truecost.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.truecost.aggregate.TripPlanner;
import com.truecost.api.dto.TripPlanRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TripControllerCoverageTest {

    private final TripPlanner planner = mock(TripPlanner.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TripController(planner)).build();

    @Test
    void outOfCoverageTripReturns422WithTypedCodeAndNoTotal() throws Exception {
        when(planner.plan(any(TripPlanRequest.class))).thenThrow(new OutOfCoverageException(
                "The destination is at (42.3601, -71.0589), which is outside the area where tolls can be priced."));

        mvc.perform(post("/api/v1/trips/plan").contentType(MediaType.APPLICATION_JSON).content(bostonBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OUT_OF_COVERAGE"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("42.3601")))
                .andExpect(jsonPath("$.options").doesNotExist())
                .andExpect(jsonPath("$.recommendation").doesNotExist());
    }

    @Test
    void malformedRequestStillReturns400() throws Exception {
        mvc.perform(post("/api/v1/trips/plan").contentType(MediaType.APPLICATION_JSON)
                        .content(bostonBody().replace("\"originLat\": 40.7505", "\"originLat\": 400")))
                .andExpect(status().isBadRequest());
    }

    private static String bostonBody() throws Exception {
        return Files.readString(Path.of("eval/golden/sample-request.json"))
                .replace("\"destLat\": 39.9500", "\"destLat\": 42.3601")
                .replace("\"destLng\": -75.1600", "\"destLng\": -71.0589");
    }
}
