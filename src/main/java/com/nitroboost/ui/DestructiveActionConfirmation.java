package com.nitroboost.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.util.Optional;

/**
 * Modal de confirmacao EXTRA-EXPLICITO para acoes destrutivas demais para o
 * {@code Alert} de confirmacao simples ja usado em outras acoes em lote (ex:
 * {@code ScanResultsView.closeAllGreen}) - hoje usado so pela desinstalacao
 * completa do OneDrive (Fase 12 Parte A, secao A.4), a acao mais drastica/
 * irreversivel do projeto ate aqui.
 *
 * Diferente do {@code Alert} padrao (botoes "OK"/"Cancelar" genericos, faceis
 * de clicar sem prestar atencao), este modal:
 *  - usa {@code Alert.AlertType.WARNING} (icone de alerta, nao de pergunta);
 *  - troca os botoes por textos EXPLICITOS que repetem o risco ("Cancelar" e
 *    "Sim, entendi os riscos - desinstalar o OneDrive"), para que so seja
 *    possivel prosseguir clicando em um botao cujo proprio texto ja avisa;
 *  - o texto de conteudo detalha, em portugues claro, o risco concreto
 *    (arquivos que existem SOMENTE na nuvem do OneDrive) antes de qualquer
 *    botao poder ser clicado.
 *
 * Compartilhado por {@link ScanResultsView} (botao de acao rapida na tabela)
 * e {@link ItemDetailView} (botao do modal de detalhes) para os dois pontos
 * de entrada exigirem exatamente o mesmo aviso, sem duplicar o texto/logica.
 */
public final class DestructiveActionConfirmation {

    private DestructiveActionConfirmation() {
        // Classe utilitaria, nao deve ser instanciada.
    }

    /**
     * Mostra o aviso e bloqueia ate o usuario responder.
     *
     * @param owner janela dona do modal (pode ser {@code null})
     * @return {@code true} somente se o usuario clicou explicitamente no botao de confirmacao
     *         (fechar a janela, apertar Esc ou clicar em "Cancelar" sempre devolve {@code false})
     */
    public static boolean confirmOneDriveUninstall(Window owner) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        if (owner != null) {
            alert.initOwner(owner);
            alert.initModality(Modality.WINDOW_MODAL);
        }
        alert.setTitle("Confirmar desinstalacao completa do OneDrive");
        alert.setHeaderText("ATENCAO: esta acao remove o OneDrive por completo deste PC");
        alert.setContentText(
                "Isso e BEM MAIS DRASTICO do que so desativar a inicializacao automatica do OneDrive.\n\n"
                + "Antes de continuar, copie para outra pasta do PC qualquer arquivo que exista SOMENTE na "
                + "nuvem do OneDrive (nao sincronizado/copiado para o disco local) - depois de desinstalar, "
                + "esses arquivos podem ficar inacessiveis ate voce reinstalar o OneDrive e entrar na conta "
                + "novamente.\n\n"
                + "Um backup do estado atual e registrado no historico antes da acao, mas a reinstalacao "
                + "automatica NAO garante restaurar a sincronizacao exatamente como estava (voce pode precisar "
                + "fazer login novamente)."
        );

        ButtonType cancelButton = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType confirmButton = new ButtonType("Sim, entendi os riscos - desinstalar o OneDrive", ButtonBar.ButtonData.OK_DONE);
        alert.getButtonTypes().setAll(cancelButton, confirmButton);

        Optional<ButtonType> choice = alert.showAndWait();
        return choice.isPresent() && choice.get() == confirmButton;
    }
}
