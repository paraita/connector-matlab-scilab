function PAterminate()
 global ('PA_jvminterface')
    disp('quitting...');
    if type(PA_jvminterface) ~= 1 then
        try
            jinvoke(PA_jvminterface,'shutdown');
        catch
        end
    end
endfunction