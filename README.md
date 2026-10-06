# Macro Curtipay Auto

Automação das tarefas da **Curtipay** (`curtipay.com/tarefas/instagram`) usando o
Serviço de Acessibilidade do Android.

O app executa o ciclo completo de cada tarefa:

1. **Identifica a tarefa** na lista da Curtipay (curtir publicação ou seguir perfil),
   inclusive lendo o `@perfil` e o valor em moedas do cartão.
2. **Seleciona o perfil do Instagram** conectado (tela "Selecione o perfil" → opção "Ativo").
3. **Clica em "Abrir no Instagram"** — incluindo o diálogo "Abrir com" do Android,
   páginas `instagram.com` no navegador e, se nada disso funcionar, um deep link direto
   para o Instagram.
4. **Executa a tarefa no Instagram**:
   * *curtir*: acha o botão "Curtir" (por descrição e, se preciso, pela posição na barra
     de ações do post) ou usa toque duplo na foto;
   * *seguir*: acha o botão "Seguir"/"Follow" no topo do perfil (nunca clica em
     "Seguindo", para não deixar de seguir sem querer).
5. **Confere se a ação pegou** e espera um tempo "humano" antes de voltar.
6. **Volta para a Curtipay** e clica em **"Concluí a tarefa"**.
7. **Volta para a lista** e repete com a próxima tarefa — tarefas já concluídas,
   em análise ou que falharam são puladas sem travar o ciclo.

## Como usar

1. Instale o APK e abra o app.
2. **1. Ativar serviço de acessibilidade** → encontre "Macro Curtipay Auto" na lista e ative.
3. **2. Botão flutuante (sobreposição)** → permita "sobrepor outros apps" (opcional, mas
   deixa você ligar/desligar e ver o status sem sair do navegador).
4. Faça login na Curtipay no navegador e deixe a página **Tarefas do Instagram** aberta.
5. Deixe o Instagram instalado e **logado**.
6. Toque em **INICIAR AUTOMAÇÃO**. O app já abre a Curtipay na página de tarefas.
7. Acompanhe pelo botão flutuante ou pelo card "Últimos eventos" na tela do app.

Para parar, toque em **PARAR AUTOMAÇÃO** (no app ou no botão flutuante).

### Modo manual (opcional)

Ligando a chave **Modo manual**, o app abre a tarefa e espera você curtir/seguir no
Instagram com a sua própria mão; quando você volta para a Curtipay, ele confirma a
tarefa e segue para a próxima. É a opção mais conservadora em relação ao Instagram.

## Se algo não funcionar

* **"Acessibilidade: DESLIGADA"** → o serviço foi desligado pelo sistema (alguns
  fabricantes fazem isso para economizar bateria). Reative e desative a otimização de
  bateria para o app.
* **"Faça login no Instagram..."** → o Instagram foi aberto na tela de login. Faça o
  login, volte ao app e inicie de novo.
* **"Aguardando novas tarefas..."** com a página já carregada → a página da Curtipay
  mudou de layout. Confira os últimos eventos no app: eles mostram o texto lido da tela
  (use isso para atualizar os marcadores em `CurtipayAccessibilityService`).
* **"Conecte um perfil do Instagram na Curtipay"** → conecte um perfil na própria
  Curtipay (a automação escolhe automaticamente a opção "Ativo").
* **Tarefa marcada como pulada** → o Instagram mudou os rótulos dos botões ou a
  publicação/perfil não abriu. O motivo aparece no log.

## Estrutura do código

| Arquivo | Papel |
| --- | --- |
| `AutomationManager.kt` | Estado global (fases, contadores, mensagens, log) compartilhado entre app, overlay e serviço. |
| `CurtipayAccessibilityService.kt` | Motor da automação: máquina de estados que lê a tela e executa cada passo. |
| `Ui.kt` | Leitura da árvore de acessibilidade e comparação de textos (sem acentos/maiúsculas). |
| `OverlayService.kt` | Botão flutuante com status, contadores e liga/desliga. |
| `MainActivity.kt` | Tela principal: permissões, modo manual, contadores e log. |

## Aviso

A Curtipay afirma em seu site que as tarefas devem ser feitas manualmente e que não usa
robôs/automação — automatizar as tarefas contraria os termos da plataforma e também os
termos do Instagram (que restringe automação da própria interface). O uso é por sua
conta e risco, principalmente para a conta do Instagram. O modo manual existe justamente
para reduzir esse risco.
