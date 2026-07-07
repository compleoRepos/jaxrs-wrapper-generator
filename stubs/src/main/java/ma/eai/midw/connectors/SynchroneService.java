package ma.eai.midw.connectors;

import ma.eai.commons.services.parsing.Envelope;

/**
 * Stub de l'interface SynchroneService utilisée par les EJBs.
 * Contrat du connecteur middleware pour les appels synchrones.
 */
public interface SynchroneService {

    /**
     * Traite une Envelope d'entrée et retourne une Envelope de réponse.
     *
     * @param envelopeIn l'Envelope contenant les données d'entrée
     * @return l'Envelope contenant la réponse du service
     * @throws Exception en cas d'erreur de traitement
     */
    Envelope process(Envelope envelopeIn) throws Exception;
}
