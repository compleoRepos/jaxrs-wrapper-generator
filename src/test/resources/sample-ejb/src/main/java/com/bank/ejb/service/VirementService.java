package com.bank.ejb.service;

import javax.ejb.Remote;

/**
 * Interface Remote pour le service de virement.
 */
@Remote
public interface VirementService {

    /**
     * Effectue un virement entre deux comptes.
     */
    String effectuerVirement(String compteDebit, String compteCredit, double montant, String motif);

    /**
     * Consulte le statut d'un virement.
     */
    String consulterStatutVirement(String referenceVirement);
}
