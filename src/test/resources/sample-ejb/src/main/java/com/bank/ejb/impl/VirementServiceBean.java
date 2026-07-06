package com.bank.ejb.impl;

import com.bank.ejb.service.VirementService;
import javax.ejb.Stateless;
import java.util.UUID;

/**
 * Implémentation du service de virement.
 */
@Stateless
public class VirementServiceBean implements VirementService {

    @Override
    public String effectuerVirement(String compteDebit, String compteCredit, double montant, String motif) {
        // Validation métier
        if (compteDebit == null || compteDebit.isBlank()) {
            throw new IllegalArgumentException("Le compte débiteur est obligatoire");
        }
        if (compteCredit == null || compteCredit.isBlank()) {
            throw new IllegalArgumentException("Le compte créditeur est obligatoire");
        }
        if (montant <= 0) {
            throw new IllegalArgumentException("Le montant doit être positif");
        }
        String reference = "VIR-" + UUID.randomUUID().toString().substring(0, 8);
        return reference;
    }

    @Override
    public String consulterStatutVirement(String referenceVirement) {
        // Logique métier : consultation du statut
        if (referenceVirement == null || referenceVirement.isBlank()) {
            throw new IllegalArgumentException("La référence du virement est obligatoire");
        }
        return "EXECUTE";
    }
}
