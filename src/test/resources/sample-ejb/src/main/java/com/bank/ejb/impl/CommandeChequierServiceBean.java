package com.bank.ejb.impl;

import com.bank.ejb.service.CommandeChequierService;
import com.bank.ejb.service.CommandeStatus;
import javax.ejb.Stateless;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Implémentation du service de commande de chéquier.
 */
@Stateless
public class CommandeChequierServiceBean implements CommandeChequierService {

    @Override
    public String enregistrerCommande(String numCompte, int nbCheques, String typeCarnet) {
        String reference = "CMD-" + UUID.randomUUID().toString().substring(0, 8);
        // Logique métier : validation et enregistrement
        if (numCompte == null || numCompte.isBlank()) {
            throw new IllegalArgumentException("Le numéro de compte est obligatoire");
        }
        if (nbCheques <= 0 || nbCheques > 100) {
            throw new IllegalArgumentException("Nombre de chèques invalide");
        }
        return reference;
    }

    @Override
    public CommandeStatus consulterEtatCommande(String numCommande) {
        CommandeStatus status = new CommandeStatus();
        status.setNumCommande(numCommande);
        status.setStatut("EN_COURS");
        status.setDateCreation("2024-01-15");
        return status;
    }

    @Override
    public void annulerCommande(String numCommande, String motif) {
        if (numCommande == null || numCommande.isBlank()) {
            throw new IllegalArgumentException("Le numéro de commande est obligatoire");
        }
        // Logique métier : annulation de la commande
        System.out.println("Commande " + numCommande + " annulée pour motif: " + motif);
    }

    @Override
    public List<CommandeStatus> listerCommandesClient(String numClient) {
        List<CommandeStatus> commandes = new ArrayList<>();
        // Logique métier : récupération des commandes du client
        CommandeStatus cmd1 = new CommandeStatus();
        cmd1.setNumCommande("CMD-001");
        cmd1.setStatut("LIVREE");
        cmd1.setDateCreation("2024-01-10");
        commandes.add(cmd1);
        return commandes;
    }
}
