package com.muoniumplayer.bedrock;

import java.awt.Component;
import java.awt.EventQueue;
import java.io.File;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import javax.swing.JDialog;
import javax.swing.JFileChooser;

/** Own AWT dialog lifetime. The GLFW/event/render thread never waits for it. */
public final class HostFileDialogs {
    @FunctionalInterface interface Picker {
        File choose(String initial, Consumer<JDialog> owner) throws Exception;
    }
    private static final class Request { volatile JDialog dialog; }
    private static volatile Request current;
    private HostFileDialogs() {}
    public static boolean active() { return current != null; }
    public static boolean chooseDirectory(String initial, Consumer<File> result) {
        return request(initial,result,HostFileDialogs::directoryPicker);
    }
    static synchronized boolean request(String initial,Consumer<File> result,Picker picker) {
        if(current!=null)return false;
        var mine=new Request();current=mine;
        EventQueue.invokeLater(()->{
            if(current!=mine)return;
            File chosen=null;
            try {
                chosen=picker.choose(initial,dialog->{
                    if(current!=mine){dialog.dispose();throw new CancellationException();}
                    mine.dialog=dialog;
                });
            }catch(CancellationException ignored) {
            }catch(Exception e) {
                System.err.println("FILE_DIALOG unavailable class="+e.getClass().getSimpleName());
            }finally {
                if(mine.dialog!=null)mine.dialog.dispose();
                final File value=chosen;
                BedrockClient.getInstance().execute(()->{
                    synchronized(HostFileDialogs.class){if(current!=mine)return;current=null;}
                    result.accept(value);
                });
            }
        });
        return true;
    }
    private static File directoryPicker(String initial,Consumer<JDialog> owner) {
        var chooser=new JFileChooser(initial) {
            @Override protected JDialog createDialog(Component parent) {
                JDialog dialog=super.createDialog(parent);
                dialog.setAlwaysOnTop(true); // explicit picker stays above the game
                owner.accept(dialog);return dialog;
            }
        };
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("选择音乐文件夹");chooser.setApproveButtonText("导入这个文件夹");
        return chooser.showOpenDialog(null)==JFileChooser.APPROVE_OPTION?chooser.getSelectedFile():null;
    }
    public static void cancel() {
        final Request old;
        synchronized(HostFileDialogs.class){old=current;current=null;}
        if(old!=null)EventQueue.invokeLater(()->{if(old.dialog!=null)old.dialog.dispose();});
    }
}
