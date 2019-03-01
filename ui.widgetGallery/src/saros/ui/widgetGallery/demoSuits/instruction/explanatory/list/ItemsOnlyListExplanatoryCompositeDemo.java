package saros.ui.widgetGallery.demoSuits.instruction.explanatory.list;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import saros.ui.widgetGallery.annotations.Demo;
import saros.ui.widgetGallery.demoSuits.AbstractDemo;
import saros.ui.widgets.ListExplanationComposite.ListExplanation;
import saros.ui.widgets.ListExplanatoryComposite;

@Demo
public class ItemsOnlyListExplanatoryCompositeDemo extends AbstractDemo {
  @Override
  public void createDemo(Composite parent) {
    final ListExplanatoryComposite explanatoryComposite =
        new ListExplanatoryComposite(parent, SWT.NONE);

    Button contentControl = new Button(explanatoryComposite, SWT.NONE);
    explanatoryComposite.setContentControl(contentControl);
    contentControl.setText("Show the list explanation...");
    contentControl.addSelectionListener(
        new SelectionAdapter() {

          @Override
          public void widgetSelected(SelectionEvent e) {
            ListExplanation expl =
                new ListExplanation((String) null, "List item #1", "List item #2", "List item #3");
            explanatoryComposite.showExplanation(expl);

            Display.getCurrent()
                .timerExec(
                    5000,
                    new Runnable() {

                      @Override
                      public void run() {
                        explanatoryComposite.hideExplanation();
                      }
                    });
          }
        });
  }
}
