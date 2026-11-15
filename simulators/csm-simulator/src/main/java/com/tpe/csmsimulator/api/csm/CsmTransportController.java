package com.tpe.csmsimulator.api.csm;

import com.tpe.csmsimulator.api.ApiProblems;
import com.tpe.csmsimulator.api.ApiProblems.FieldError;
import com.tpe.csmsimulator.application.CsmSimulation;
import com.tpe.csmsimulator.application.CsmSimulation.Refused;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The CSM transport, {@code POST /csm/v1/messages} (specs/17-csm-simulator.md §3.1, P-06): a valid envelope is
 * {@code 202} and processed asynchronously; one that is not a valid CSM envelope, or names a transfer the simulator
 * cannot read, is {@code 400 INVALID_ENVELOPE}, with the part that failed and its violation codes in {@code errors}.
 */
@RestController
class CsmTransportController {

    private final CsmSimulation simulation;

    CsmTransportController(CsmSimulation simulation) {
        this.simulation = simulation;
    }

    /** The body is read as bytes: XML without a charset is UTF-8. */
    @PostMapping("/csm/v1/messages")
    ResponseEntity<ProblemDetail> receive(@RequestBody byte[] body) {
        if (simulation.receive(new String(body, StandardCharsets.UTF_8)) instanceof Refused refused) {
            // the codes only: a violation message may quote the input
            return ResponseEntity.badRequest().body(ApiProblems.badRequest(ApiProblems.INVALID_ENVELOPE,
                    "invalid " + refused.part(), refused.violations().stream()
                            .map(v -> new FieldError(refused.part().name(), v.code())).distinct().toList()));
        }
        return ResponseEntity.accepted().build();
    }
}
