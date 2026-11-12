package com.tpe.vopservice.api.responder;

import com.tpe.paymentdomain.Bic;
import com.tpe.vopservice.application.responding.RespondingService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The VoP scheme endpoint other PSPs call (specs/13-vop-service.md §3.2, UC-03). The requesting PSP is checked first,
 * so a caller that is not a participant learns nothing about the request, valid or not.
 */
@RestController
class ResponderController {

    private final RespondingService responding;

    ResponderController(RespondingService responding) {
        this.responding = responding;
    }

    @PostMapping("/vop/v1/verifications")
    SchemeVerificationResponse verify(@RequestBody SchemeVerificationRequest request) {
        Bic requestingPsp = responding.authenticate(request.requestingPspBic());
        return SchemeVerificationResponse.of(request.requestId(), responding.respond(request.toCommand(requestingPsp)));
    }
}
