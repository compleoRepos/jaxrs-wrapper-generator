package com.bank.ejb.service;

import javax.ejb.Remote;

/**
 * Interface Remote pour le service de commande de chéquier.
 */
@Remote
public interface CommandeChequierService {

    /**
     * Enregistre une nouvelle commande de chéquier.
     */
    String enregistrerCommande(String numCompte, int nbCheques, String typeCarnet);

    /**
     * Consulte l'état d'une commande.
     */
    CommandeStatus consulterEtatCommande(String numCommande);

    /**
     * Annule une commande en cours.
     */
    void annulerCommande(String numCommande, String motif);

    /**
     * Liste les commandes d'un client.
     */
    java.util.List<CommandeStatus> listerCommandesClient(String numClient);
}
