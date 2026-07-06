package com.bank.ejb.service;

public class CommandeStatus {
    private String numCommande;
    private String statut;
    private String dateCreation;

    public CommandeStatus() {}

    public String getNumCommande() { return numCommande; }
    public void setNumCommande(String numCommande) { this.numCommande = numCommande; }

    public String getStatut() { return statut; }
    public void setStatut(String statut) { this.statut = statut; }

    public String getDateCreation() { return dateCreation; }
    public void setDateCreation(String dateCreation) { this.dateCreation = dateCreation; }
}
